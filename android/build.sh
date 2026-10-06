#!/usr/bin/env bash
# Builds the phone app and copies it to ../PhoneMic.apk
#   ./build.sh          unit tests + release APK
#   ./build.sh quick    release APK only
#   ./build.sh <tasks>  any gradle tasks
# Needs a JDK and the Android SDK in ../.toolchain (jdk/, android-sdk/, gradle-home/).
set -euo pipefail
cd "$(dirname "$0")"
TC="$(cd .. && pwd)/.toolchain"
export JAVA_HOME="$TC/jdk" ANDROID_HOME="$TC/android-sdk" GRADLE_USER_HOME="$TC/gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"
echo "sdk.dir=$ANDROID_HOME" > local.properties
if [ ! -f phone-mic-release.jks ]; then
  echo "No signing key yet: making android/phone-mic-release.jks. BACK IT UP: without it no update of the app can be installed over this one."
  keytool -genkeypair -keystore phone-mic-release.jks -storepass phone-mic-local -keypass phone-mic-local \
    -alias phonemic -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Phone Mic" >/dev/null 2>&1
fi
if [ "${1:-}" = quick ]; then ./gradlew --console=plain -q assembleRelease
elif [ $# -gt 0 ]; then exec ./gradlew --console=plain "$@"
else ./gradlew --console=plain testDebugUnitTest assembleRelease; fi
cp app/build/outputs/apk/release/app-release.apk ../PhoneMic.apk
ls -la ../PhoneMic.apk
