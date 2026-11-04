#!/bin/sh
# Builds the APK without Gradle, inside a container with the Android SDK.
# Usage: ./build.sh   -> build/elder-launcher.apk
#
# The first run creates a signing key in signing/ (git-ignored) with a random password.
# Keep that folder: Android only accepts updates signed with the same key.
set -e
cd "$(dirname "$0")"
IMG=ghcr.io/cirruslabs/android-sdk:34
mkdir -p build signing
chmod 700 signing
if [ ! -f signing/elder-launcher.keystore ]; then
  umask 077
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n' > signing/password
  docker run --rm -e HOME=/tmp -u "$(id -u):$(id -g)" -v "$PWD":/p -w /p "$IMG" sh -ec '
    keytool -genkeypair -keystore signing/elder-launcher.keystore \
      -storepass "$(cat signing/password)" -keypass "$(cat signing/password)" \
      -alias elder-launcher -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Elder Launcher"
  '
fi
docker run --rm -e HOME=/tmp -u "$(id -u):$(id -g)" -v "$PWD":/p -w /p "$IMG" sh -ec '
BT=$ANDROID_HOME/build-tools/34.0.0
JAR=$ANDROID_HOME/platforms/android-34/android.jar
rm -rf build/obj build/gen build/res.zip build/*.apk build/dex
mkdir -p build/obj build/gen build/dex
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $JAR --manifest AndroidManifest.xml -A assets \
  --java build/gen build/res.zip --min-sdk-version 30 --target-sdk-version 30
javac -parameters -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -classpath $JAR -d build/obj \
  $(find src build/gen -name "*.java")
$BT/d8 --release --min-api 30 --lib $JAR --output build/dex $(find build/obj -name "*.class")
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks signing/elder-launcher.keystore --ks-pass file:signing/password \
  --out build/elder-launcher.apk build/aligned.apk
rm -f build/base.apk build/unsigned.apk build/aligned.apk build/elder-launcher.apk.idsig
'
ls -l build/elder-launcher.apk
