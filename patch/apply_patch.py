#!/usr/bin/env python3
"""Patches an apktool-decoded Game Recorder+ so it works without Samsung's servers.

Usage: apply_patch.py <apktool_dir> <helper_smali_dir>
"""
import os
import re
import shutil
import sys

ROOT = sys.argv[1]
HELPER = sys.argv[2]

PKG = "Lcom/sec/game/gamecast/"
DDB = "smali/com/sec/game/gamecast/connectivity/DDBManager.smali"
GPM = "smali/com/sec/game/gamecast/common/controller/GamePackageManager.smali"
START = "smali/com/sec/game/gamecastglobal/activity/StartActivity.smali"
REG = "smali/com/sec/game/gamecastglobal/activity/RegisterGamelistActivity.smali"


def path(rel):
    return os.path.join(ROOT, rel)


def read(rel):
    with open(path(rel), encoding="utf-8") as f:
        return f.read()


def write(rel, text):
    with open(path(rel), "w", encoding="utf-8") as f:
        f.write(text)


def replace_method(text, header, body):
    """Replaces the whole method that starts with `header` (exact line)."""
    start = text.find(header + "\n")
    if start < 0:
        raise SystemExit("method not found: " + header)
    end = text.find(".end method", start)
    return text[:start] + header + "\n" + body.strip("\n") + "\n.end method" + text[end + len(".end method"):]


def replace_once(text, old, new, label):
    if text.count(old) != 1:
        raise SystemExit("expected exactly one match for %s, got %d" % (label, text.count(old)))
    return text.replace(old, new)


# Callback returns an empty list right away, as if the server answered "nothing to report".
EMPTY_LIST_CALLBACK = """
    .locals 1
    if-eqz {cb}, :done
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {{v0}}, Ljava/util/ArrayList;-><init>()V
    invoke-interface {{{cb}, v0}}, Lcom/sec/game/gamecast/connectivity/DDBManager$Callback;->onSuccess(Ljava/lang/Object;)V
    :done
    return-void
"""

# 1) DDBManager: no more DynamoDB queries.
t = read(DDB)
for header, cb in [
    (".method public static getUpdateInfo(Ljava/lang/String;Ljava/lang/String;Lcom/sec/game/gamecast/connectivity/DDBManager$Callback;)V", "p2"),
    (".method public static getModelSpec(Ljava/lang/String;ILcom/sec/game/gamecast/connectivity/DDBManager$Callback;)V", "p2"),
    (".method public static getModelSpec(ILcom/sec/game/gamecast/connectivity/DDBManager$Callback;)V", "p1"),
    (".method public static getRecordableModel(Ljava/lang/String;ILcom/sec/game/gamecast/connectivity/DDBManager$Callback;)V", "p2"),
    (".method public static getForbiddenGamesList(Lcom/sec/game/gamecast/connectivity/DDBManager$Callback;)V", "p0"),
    (".method public static compareRecordablePackages(Ljava/util/List;Lcom/sec/game/gamecast/connectivity/DDBManager$Callback;)V", "p1"),
]:
    t = replace_method(t, header, EMPTY_LIST_CALLBACK.format(cb=cb))
for header in [
    ".method public static compareRecordablePackage(Ljava/lang/String;Lcom/sec/game/gamecast/connectivity/DDBManager$Callback;)V",
    ".method public static getDefaultConfig(Lcom/sec/game/gamecast/connectivity/DDBManager$Callback;)V",
]:
    t = replace_method(t, header, "    .locals 0\n    return-void")
# getRegion() must never return null (used when registering a game).
t = replace_method(t, ".method public static getRegion()Lcom/amazonaws/regions/Regions;", """
    .locals 1
    sget-object v0, Lcom/sec/game/gamecast/connectivity/DDBManager;->_region:Lcom/amazonaws/regions/Regions;
    if-nez v0, :ok
    sget-object v0, Lcom/amazonaws/regions/Regions;->AP_NORTHEAST_1:Lcom/amazonaws/regions/Regions;
    :ok
    return-object v0
""")
write(DDB, t)

# 2) GamePackageManager: game list comes from the local database only.
G = "Lcom/sec/game/gamecast/common/controller/GamePackageManager;"
t = read(GPM)
t = replace_method(t, ".method private performGamesAllow()V", """
    .locals 3
    invoke-virtual {p0}, %(G)s->refreshGameLists()V
    iget-object v0, p0, %(G)s->context:Landroid/content/Context;
    const-string v1, "loaded_allow_games"
    const/4 v2, 0x1
    invoke-static {v0, v1, v2}, Lcom/sec/game/gamecast/common/utility/CommonUtil;->putSharedPreferenceBoolean(Landroid/content/Context;Ljava/lang/String;Z)V
    iput-boolean v2, p0, %(G)s->isPreloaded:Z
    invoke-direct {p0}, %(G)s->checkPreload()V
    return-void
""" % {"G": G})
t = replace_method(t, ".method private performGamesForbidden()V", "    .locals 0\n    return-void")
# Scan for newly installed games every time the list is refreshed.
old = ".method public refreshGameLists()V\n"
i = t.find(old)
if i < 0:
    raise SystemExit("refreshGameLists not found")
j = t.find(".prologue", i)
k = t.find("\n", j) + 1
inject = ("    iget-object v0, p0, %s->context:Landroid/content/Context;\n"
          "    invoke-static {v0}, Lcom/offline/OfflineHelper;->syncAllowedGames(Landroid/content/Context;)V\n" % G)
locals_match = re.search(r"\.locals (\d+)", t[i:j])
if not locals_match or int(locals_match.group(1)) < 1:
    raise SystemExit("refreshGameLists has no free register")
t = t[:k] + inject + t[k:]
write(GPM, t)

# 3) StartActivity: skip end-of-service popup, Samsung-only and internet checks.
t = read(START)
t = replace_once(t,
    "invoke-direct {p0}, Lcom/sec/game/gamecastglobal/activity/StartActivity;->runEosCheck()V",
    "invoke-direct {p0}, Lcom/sec/game/gamecastglobal/activity/StartActivity;->runOnCreate()V",
    "runEosCheck call")
t = replace_once(t,
    "    invoke-static {}, Lcom/sec/game/gamecast/common/utility/DeviceUtil;->isSamsungDevice()Z\n\n    move-result v5\n",
    "    const/4 v5, 0x1\n",
    "isSamsungDevice")
t = replace_once(t,
    "    invoke-static {v0, v5}, Lcom/sec/game/gamecast/common/controller/utility/NetworkConfig;->hasInternet(Landroid/content/Context;Z)Z\n\n    move-result v5\n",
    "    const/4 v5, 0x1\n",
    "hasInternet")
write(START, t)

# 4) RegisterGamelistActivity: register the chosen app locally and report success.
R = "Lcom/sec/game/gamecastglobal/activity/RegisterGamelistActivity;"
t = read(REG)
t = replace_method(t,
    ".method private performGameGlobalAllow(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
    """
    .locals 3
    invoke-static {p0, p1}, Lcom/offline/OfflineHelper;->registerGame(Landroid/content/Context;Ljava/lang/String;)V
    new-instance v0, Lcom/sec/game/gamecast/common/controller/network/data/GameGlobalAllow;
    invoke-direct {v0}, Lcom/sec/game/gamecast/common/controller/network/data/GameGlobalAllow;-><init>()V
    const-string v1, "0"
    invoke-virtual {v0, v1}, Lcom/sec/game/gamecast/common/controller/network/data/GameGlobalAllow;->setResult_code(Ljava/lang/String;)V
    new-instance v2, Lcom/sec/game/gamecastglobal/activity/RegisterGamelistActivity$3;
    invoke-direct {v2, p0, p0}, Lcom/sec/game/gamecastglobal/activity/RegisterGamelistActivity$3;-><init>(%(R)sLandroid/content/Context;)V
    invoke-virtual {v2, v0}, Lcom/sec/game/gamecastglobal/activity/RegisterGamelistActivity$3;->onPostExecute(Lcom/sec/game/gamecast/common/controller/network/data/GameGlobalAllow;)V
    return-void
""" % {"R": R})
write(REG, t)

# 5) Add the helper class.
dst = path("smali/com/offline")
os.makedirs(dst, exist_ok=True)
shutil.copy(os.path.join(HELPER, "com/offline/OfflineHelper.smali"), dst)
print("patch applied")
