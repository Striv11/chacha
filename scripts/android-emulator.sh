#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAVA_17="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
AVD_NAME="chacha_api35"
SYSTEM_IMAGE="system-images;android-35;google_apis;arm64-v8a"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"

step() { printf '\n\033[1;36m%s\033[0m\n' "$1"; }
die() { printf '\n\033[0;31m%s\033[0m\n' "$1"; exit 1; }

[[ "$(uname -s)" == "Darwin" ]] || die "This emulator launcher currently supports macOS."
[[ -d "$JAVA_17" ]] || die "Java 17 is missing. Install it with: brew install openjdk@17"
export JAVA_HOME="$JAVA_17"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export AVDMANAGER_OPTS="-Dcom.android.sdkmanager.toolsdir=$SDK_ROOT/cmdline-tools/latest"
export PATH="$SDK_ROOT/emulator:$SDK_ROOT/platform-tools:/opt/homebrew/bin:$PATH"

command -v sdkmanager >/dev/null || die "Android command-line tools are missing. Install them with: brew install android-commandlinetools"
command -v avdmanager >/dev/null || die "avdmanager is missing from the Android command-line tools."
[[ -x "$ADB" ]] || die "ADB is missing from $SDK_ROOT/platform-tools."

step "Preparing the Android 15 emulator"
if [[ ! -x "$EMULATOR" || ! -f "$SDK_ROOT/system-images/android-35/google_apis/arm64-v8a/package.xml" ]]; then
    set +o pipefail
    yes | sdkmanager --sdk_root="$SDK_ROOT" "emulator" "$SYSTEM_IMAGE"
    status="${PIPESTATUS[1]}"
    set -o pipefail
    [[ "$status" -eq 0 ]] || die "Android emulator packages could not be installed."
fi

if ! avdmanager list avd | grep -q "Name: $AVD_NAME"; then
    printf 'no\n' | avdmanager create avd --force --name "$AVD_NAME" --package "$SYSTEM_IMAGE"
    avd_config="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
    sed -i '' \
        -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=6G/' \
        -e 's/^hw.dPad=.*/hw.dPad=no/' \
        -e 's/^hw.keyboard=.*/hw.keyboard=yes/' \
        -e 's/^hw.lcd.density=.*/hw.lcd.density=420/' \
        -e 's/^hw.lcd.height=.*/hw.lcd.height=2400/' \
        -e 's/^hw.lcd.width=.*/hw.lcd.width=1080/' \
        -e 's/^hw.mainKeys=.*/hw.mainKeys=no/' \
        -e 's/^hw.ramSize=.*/hw.ramSize=4096/' \
        "$avd_config"
fi

step "Starting $AVD_NAME"
"$ADB" start-server >/dev/null
if ! "$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" {found=1} END {exit !found}'; then
    mkdir -p "$ROOT/data"
    nohup "$EMULATOR" -avd "$AVD_NAME" -no-boot-anim -no-snapshot-load -no-snapshot-save \
        >"$ROOT/data/emulator.log" 2>&1 &
fi

for _ in {1..180}; do
    if [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        break
    fi
    sleep 1
done
[[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] || die "The emulator did not finish booting. See data/emulator.log."

step "Building and installing Chacha"
(cd "$ROOT/android" && ./gradlew assembleDebug)
"$ADB" install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
sleep 1

[[ -f "$ROOT/.env" ]] || die "The backend .env file is missing. Run the Chacha installer first."
token="$(sed -n 's/^BRAIN_TOKEN=//p' "$ROOT/.env" | tail -1)"
[[ -n "$token" ]] || die "BRAIN_TOKEN is empty in .env."
backend_host="$(tailscale ip -4 2>/dev/null | head -1 || true)"
[[ -n "$backend_host" ]] || backend_host="10.0.2.2"

step "Connecting the emulator to the local Chacha backend"
"$ADB" shell am force-stop com.pocket.brain
"$ADB" shell am start \
    -n com.pocket.brain/.MainActivity \
    --ez chacha_emulator_setup true \
    --es chacha_url "http://$backend_host:8765" \
    --es chacha_token "$token" >/dev/null
sleep 1
accessibility="com.pocket.brain/com.pocket.brain.ScreenService"
for _ in {1..3}; do
    "$ADB" shell settings put secure enabled_accessibility_services "$accessibility"
    "$ADB" shell settings put secure accessibility_enabled 1
    sleep 1
    [[ "$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')" == "$accessibility" ]] && break
done
[[ "$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')" == "$accessibility" ]] || \
    die "Android disabled Chacha's accessibility service during setup."

printf '\n\033[0;32mChacha is ready in the Android emulator.\033[0m\n'
printf 'Ask: "Open Settings and show me the battery usage screen."\n'
printf 'Logs: adb logcat -s Chacha ChachaDictate\n'
