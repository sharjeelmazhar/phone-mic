#!/usr/bin/env bash
# Installs the phone-mic setup for the current user. No sudo needed. Safe to re-run.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"

for c in adb scrcpy pw-loopback pactl; do
    command -v $c >/dev/null || { echo "MISSING: $c  ->  sudo apt install adb scrcpy pipewire-bin pulseaudio-utils"; exit 1; }
done

mkdir -p ~/.local/bin ~/.config/systemd/user ~/.local/share/applications ~/.config/phone-mic
install -m 755 "$HERE/bin/phone-mic" "$HERE/bin/phone-mic-stream" ~/.local/bin/
install -m 644 "$HERE"/systemd/*.service ~/.config/systemd/user/
sed "s|@HOME@|$HOME|g" "$HERE/phone-mic-toggle.desktop" > ~/.local/share/applications/phone-mic-toggle.desktop
[ -f ~/.config/phone-mic/config ] || install -m 644 "$HERE/config.example" ~/.config/phone-mic/config

pkill -f 'pw-loopback --name phone-mic-test' 2>/dev/null || true   # leftover from first manual test
systemctl --user daemon-reload
systemctl --user enable adb-server.service phone-mic-device.service phone-mic.service
systemctl --user stop phone-mic.service 2>/dev/null || true
adb kill-server >/dev/null 2>&1 || true
systemctl --user restart adb-server.service
systemctl --user restart phone-mic-device.service
sleep 2
systemctl --user restart phone-mic.service
echo "Installed. Check with:  phone-mic status"
