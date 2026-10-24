#!/bin/sh
# Rebuilds the offline APK from the original Game Recorder+ 0.0.41 APK.
# Usage: patch/build.sh <original.apk> <work_dir>
# Needs: java (17+), python3, curl, unzip, zip.
set -e
ORIG=$(realpath "$1"); W=$(realpath -m "$2"); HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$W/tools" && cd "$W"
T="$W/tools"
[ -f "$T/apktool.jar" ] || curl -sSfL -o "$T/apktool.jar" https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar
[ -f "$T/signer.jar" ] || curl -sSfL -o "$T/signer.jar" https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar
[ -f "$T/android-all-28.jar" ] || curl -sSfL --retry 5 --retry-delay 10 -o "$T/android-all-28.jar" https://repo1.maven.org/maven2/org/robolectric/android-all/9-robolectric-4913185-2/android-all-9-robolectric-4913185-2.jar
if [ ! -d "$T/d2j" ]; then
  curl -sSfL -o "$T/d2j.zip" https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip
  unzip -qo "$T/d2j.zip" -d "$T/d2j"
fi
D2J=$(dirname "$(find -L "$T/d2j" -name d2j-dex2jar.sh | head -1)")

rm -rf decoded helper && mkdir -p helper/classes helper/fake
java -jar "$T/apktool.jar" d -f "$ORIG" -o decoded
sh "$D2J/d2j-dex2jar.sh" -f -o helper/app-classes.jar "$ORIG"
javac -nowarn --release 8 -cp "$T/android-all-28.jar:helper/app-classes.jar" -d helper/classes $(find "$HERE/java" -name "*.java")
jar cf helper/helper.jar -C helper/classes .
sh "$D2J/d2j-jar2dex.sh" -f -o helper/fake/classes.dex helper/helper.jar
(cd helper/fake && zip -q ../fake.apk classes.dex)
java -jar "$T/apktool.jar" d -f -r helper/fake.apk -o helper/smali
python3 -I "$HERE/apply_patch.py" decoded helper/smali/smali
java -jar "$T/apktool.jar" b decoded -o unsigned.apk
java -jar "$T/signer.jar" -a unsigned.apk -o signed
echo "Done: $(ls "$W"/signed/*.apk)"
