#!/bin/zsh
# Gradle-free APK build: aapt2 + javac + d8 + apksigner
set -e
cd "$(dirname "$0")"
SDK=~/Library/Android/sdk
BT=$SDK/build-tools/36.0.0
PLAT=$SDK/platforms/android-35/android.jar
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build && mkdir -p build/classes build/dex
$BT/aapt2 link -o build/base.apk -I $PLAT --manifest AndroidManifest.xml --min-sdk-version 31 --target-sdk-version 34
javac -source 11 -target 11 -nowarn -classpath $PLAT -d build/classes $(find src -name '*.java') 2>&1 | grep -v "warning" || true
$BT/d8 --min-api 31 --lib $PLAT --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Debug" >/dev/null 2>&1
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --out build/xrc.apk build/aligned.apk
echo "built: $(pwd)/build/xrc.apk"
