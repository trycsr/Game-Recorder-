package com.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.sec.game.gamecast.common.controller.GameCastDBManager;
import com.sec.game.gamecast.common.controller.network.data.GamesAllowData;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces the Samsung servers (shut down in 2018) with local logic.
 * The list of recordable games now lives only in the app's own database.
 */
public final class OfflineHelper {
    private static final String PREFS = "offline_mode";
    private static final String KEY_AUTO_ADDED = "auto_added";
    // ApplicationInfo.FLAG_IS_GAME (deprecated, still set by many games)
    private static final int FLAG_IS_GAME = 0x02000000;
    // ApplicationInfo.CATEGORY_GAME (API 26+)
    private static final int CATEGORY_GAME = 0;

    private OfflineHelper() {
    }

    /** Adds installed apps that declare themselves as games to the local list (once per app). */
    public static void syncAllowedGames(Context ctx) {
        try {
            GameCastDBManager db = GameCastDBManager.getInstance();
            List<GamesAllowData> current = db.getAllowGameDataList(ctx);
            Set<String> known = new HashSet<String>();
            int maxSeq = 0;
            for (GamesAllowData d : current) {
                known.add(d.getPkg_name());
                if (d.getGame_seq() > maxSeq) {
                    maxSeq = d.getGame_seq();
                }
            }
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, 0);
            Set<String> autoAdded = new HashSet<String>(sp.getStringSet(KEY_AUTO_ADDED, new HashSet<String>()));
            PackageManager pm = ctx.getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);
            boolean changed = false;
            for (PackageInfo pi : pkgs) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null || (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
                String pkg = pi.packageName;
                if (pkg.equals(ctx.getPackageName()) || known.contains(pkg) || autoAdded.contains(pkg)) {
                    continue;
                }
                if (!isGame(ai)) {
                    continue;
                }
                maxSeq++;
                insert(ctx, db, pm, ai, pkg, maxSeq);
                known.add(pkg);
                autoAdded.add(pkg);
                changed = true;
            }
            if (changed) {
                sp.edit().putStringSet(KEY_AUTO_ADDED, autoAdded).apply();
            }
        } catch (Throwable t) {
            // Never break the app because of the game scan.
        }
    }

    /** Registers any installed app as a recordable game, without contacting a server. */
    public static void registerGame(Context ctx, String pkg) {
        if (pkg == null) {
            return;
        }
        try {
            if (GameCastDBManager.hasAllowGameInfo(ctx, pkg)) {
                return;
            }
            GameCastDBManager db = GameCastDBManager.getInstance();
            int maxSeq = 0;
            for (GamesAllowData d : db.getAllowGameDataList(ctx)) {
                if (d.getGame_seq() > maxSeq) {
                    maxSeq = d.getGame_seq();
                }
            }
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            insert(ctx, db, pm, ai, pkg, maxSeq + 1);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static void insert(Context ctx, GameCastDBManager db, PackageManager pm, ApplicationInfo ai, String pkg, int seq) {
        GamesAllowData data = new GamesAllowData();
        data.setGame_seq(seq);
        String name;
        try {
            name = ai.loadLabel(pm).toString();
        } catch (Throwable t) {
            name = pkg;
        }
        data.setGame_name(name);
        data.setPkg_name(pkg);
        data.setStatus("I");
        db.insertAllowGameData(ctx, data);
    }

    private static boolean isGame(ApplicationInfo ai) {
        if ((ai.flags & FLAG_IS_GAME) != 0) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                int category = ApplicationInfo.class.getField("category").getInt(ai);
                return category == CATEGORY_GAME;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }
}
