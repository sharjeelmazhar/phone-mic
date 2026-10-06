#!/usr/bin/env bash
# Test the phone app in an Android emulator (never on a real phone: every adb call names the emulator).
#   tools/emu.sh create              make the AVD (once)
#   tools/emu.sh start [tone]        boot it; "tone": its microphone hears a 440 Hz test tone from this computer
#   tools/emu.sh install | shot NAME | tap X Y | ui | stop | <any adb args>
# Needs the JDK + Android SDK + system image android-35 in .toolchain/.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; TC="$ROOT/.toolchain"
export ANDROID_SDK_ROOT="$TC/android-sdk" ANDROID_HOME="$TC/android-sdk" ANDROID_AVD_HOME="$ROOT/.avd" JAVA_HOME="$TC/jdk"
SERIAL=emulator-5560
ADB="adb -s $SERIAL"
case "$1" in
  create) echo no | "$TC"/android-sdk/cmdline-tools/latest/bin/avdmanager create avd -n phonemic \
            -k "system-images;android-35;google_apis;x86_64" -d pixel_6 ;;
  start)
    mkdir -p "$ROOT/logs"
    env=()
    if [ "${2:-}" = tone ]; then
        # a private null sink playing a tone; the emulator records from its monitor (default input stays untouched)
        pactl list short sinks | grep -q pmtone || pactl load-module module-null-sink sink_name=pmtone \
            sink_properties=device.description=PhoneMicTestTone >/dev/null
        python3 -c "
import math, struct, sys
sys.stdout.buffer.write(b''.join(struct.pack('<h', int(12000*math.sin(2*math.pi*440*i/48000))) for i in range(48000)))" > "$ROOT/logs/tone.raw"
        ( while :; do pw-cat --playback --raw --rate 48000 --channels 1 --format s16 --target pmtone "$ROOT/logs/tone.raw" || break; done ) \
            > /dev/null 2>&1 & echo $! > "$ROOT/logs/tone.pid"
        env=(PULSE_SOURCE=pmtone.monitor PULSE_SERVER="unix:${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/pulse/native")
    fi
    env "${env[@]}" nohup "$TC/android-sdk/emulator/emulator" -avd phonemic -port 5560 -no-window -no-boot-anim -no-snapshot \
        -gpu swiftshader_indirect -allow-host-audio > "$ROOT/logs/emulator.log" 2>&1 &
    $ADB wait-for-device
    until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
    $ADB emu avd hostmicon >/dev/null
    echo booted ;;
  stop) $ADB emu kill; [ -f "$ROOT/logs/tone.pid" ] && { pkill -P "$(cat "$ROOT/logs/tone.pid")"; kill "$(cat "$ROOT/logs/tone.pid")"; rm "$ROOT/logs/tone.pid"; }
        m=$(pactl list short modules | awk '/sink_name=pmtone/{print $1}'); [ -n "$m" ] && pactl unload-module "$m"; true ;;
  install) $ADB install -r "$ROOT/PhoneMic.apk" ;;
  shot) mkdir -p "$ROOT/logs/shots"; $ADB exec-out screencap -p > "$ROOT/logs/shots/$2.png"; echo "$ROOT/logs/shots/$2.png" ;;
  ui) $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null && $ADB exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
for m in re.finditer(r'text=\"([^\"]+)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]', sys.stdin.read()):
    t, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:]); print(f'{(x1+x2)//2:5} {(y1+y2)//2:5}  {t}')" ;;
  tap) $ADB shell input tap "$2" "$3" ;;
  *) $ADB "$@" ;;
esac
