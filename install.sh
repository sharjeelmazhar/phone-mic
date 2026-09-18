#!/usr/bin/env bash
# Installs the phone-mic setup for the current user. No sudo needed. Safe to re-run.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"

# --- requirements -------------------------------------------------------------------------------
if   command -v apt    >/dev/null; then HINT="sudo apt install adb scrcpy pipewire-bin pulseaudio-utils"
elif command -v pacman >/dev/null; then HINT="sudo pacman -S android-tools android-udev scrcpy pipewire pipewire-pulse wireplumber libpulse"
elif command -v dnf    >/dev/null; then HINT="sudo dnf install android-tools pipewire-utils pulseaudio-utils   (scrcpy: see https://github.com/Genymobile/scrcpy/blob/master/doc/linux.md)"
else HINT="install adb, scrcpy (>= 2.1), pipewire (pw-loopback) and pulseaudio-utils (pactl) with your package manager"; fi
for c in adb scrcpy pw-loopback pactl; do
    command -v $c >/dev/null || { echo "MISSING: $c  ->  $HINT"; exit 1; }
done
ver=$(scrcpy --version 2>/dev/null | head -1 | grep -oE '[0-9]+\.[0-9]+' | head -1)
if [ -n "$ver" ] && [ "$(printf '%s\n2.1\n' "$ver" | sort -V | head -1)" != "2.1" ]; then
    echo "scrcpy $ver is too old: microphone capture needs scrcpy >= 2.1 (Debian 12 ships 1.25). See https://github.com/Genymobile/scrcpy/blob/master/doc/linux.md"; exit 1
fi
command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1 || { echo "This setup needs systemd user services (systemctl --user)."; exit 1; }
pactl info 2>/dev/null | grep -q "PulseAudio (on PipeWire" || echo "Warning: the audio server does not look like PipeWire; the virtual mic needs PipeWire + WirePlumber."

mkdir -p ~/.local/bin ~/.config/systemd/user ~/.local/share/applications ~/.config/phone-mic
install -m 755 "$HERE/bin/phone-mic" "$HERE/bin/phone-mic-stream" "$HERE/bin/phone-mic-gsconnect.js" ~/.local/bin/
install -m 644 "$HERE"/systemd/*.service ~/.config/systemd/user/
sed "s|@HOME@|$HOME|g" "$HERE/phone-mic-toggle.desktop" > ~/.local/share/applications/phone-mic-toggle.desktop
[ -f ~/.config/phone-mic/config ] || install -m 644 "$HERE/config.example" ~/.config/phone-mic/config

# Optional GNOME Quick Settings tile (only if GNOME Shell is present)
UUID=phone-mic@sharjeelmazhar.github.io
if command -v gnome-shell >/dev/null; then
    mkdir -p ~/.local/share/gnome-shell/extensions/$UUID
    cp -r "$HERE"/gnome-extension/$UUID/. ~/.local/share/gnome-shell/extensions/$UUID/
    # `gnome-extensions enable` only works once the shell has loaded the extension (at login). Writing the
    # setting directly makes it enabled at the next login too, so no visit to Extension Manager is needed.
    cur=$(gsettings get org.gnome.shell enabled-extensions 2>/dev/null || echo "@as []")
    if ! grep -q "'$UUID'" <<<"$cur"; then
        if [ "$cur" = "@as []" ] || [ "$cur" = "[]" ]; then new="['$UUID']"; else new="${cur%]}, '$UUID']"; fi
        gsettings set org.gnome.shell enabled-extensions "$new" 2>/dev/null || true
    fi
    gnome-extensions enable $UUID 2>/dev/null || true
    echo "GNOME tile installed: 'Phone Mic' appears in Quick Settings after you log out and back in once."
fi

systemctl --user daemon-reload
systemctl --user enable adb-server.service phone-mic-device.service phone-mic.service
systemctl --user stop phone-mic.service 2>/dev/null || true
adb kill-server >/dev/null 2>&1 || true
systemctl --user restart adb-server.service
systemctl --user restart phone-mic-device.service
sleep 2
systemctl --user restart phone-mic.service
# Optional: "Phone Mic ON/OFF/TOGGLE" buttons in the KDE Connect app on the phone (needs GSConnect + a paired phone).
# Harmless if GSConnect is missing; can be run any time later with:  phone-mic kdeconnect
~/.local/bin/phone-mic kdeconnect add 2>/dev/null | head -1 || true
echo "Installed. Check with:  phone-mic status"
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) echo "Note: ~/.local/bin is not in PATH yet (it will be after the next login). Until then use: ~/.local/bin/phone-mic";; esac
