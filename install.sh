#!/usr/bin/env bash
# Installs the phone-mic setup for the current user, into the home folder. No sudo needed. Safe to re-run.
# For distros without apt. On Ubuntu / Debian use the .deb instead (see README): it also brings updates.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ -x /usr/bin/phone-mic ]; then echo "phone-mic is already installed as a package (/usr/bin/phone-mic); nothing to do. Updates come with apt."; exit 0; fi

# --- requirements -------------------------------------------------------------------------------
if   command -v apt    >/dev/null; then HINT="sudo apt install pipewire-bin pulseaudio-utils python3"
elif command -v pacman >/dev/null; then HINT="sudo pacman -S pipewire pipewire-pulse wireplumber libpulse python"
elif command -v dnf    >/dev/null; then HINT="sudo dnf install pipewire-utils pulseaudio-utils python3"
else HINT="install pipewire (pw-loopback, pw-cat), pulseaudio-utils (pactl) and python3 with your package manager"; fi
for c in pw-loopback pw-cat pactl python3; do
    command -v $c >/dev/null || { echo "MISSING: $c  ->  $HINT"; exit 1; }
done
command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1 || { echo "This setup needs systemd user services (systemctl --user)."; exit 1; }
pactl info 2>/dev/null | grep -q "PulseAudio (on PipeWire" || echo "Warning: the audio server does not look like PipeWire; the virtual mic needs PipeWire + WirePlumber."

mkdir -p ~/.local/bin ~/.config/systemd/user ~/.local/share/applications ~/.config/phone-mic
install -m 755 "$HERE/bin/phone-mic" "$HERE/bin/phone-mic-daemon" "$HERE/bin/phone-mic-gsconnect.js" ~/.local/bin/
# the files in this folder carry the paths of the .deb (/usr/...): point them at the home folder instead
for f in "$HERE"/systemd/*.service; do
    sed "s|/usr/lib/phone-mic/|%h/.local/bin/|" "$f" > ~/.config/systemd/user/"$(basename "$f")"
done
sed "s|/usr/bin/phone-mic|$HOME/.local/bin/phone-mic|" "$HERE/phone-mic-toggle.desktop" > ~/.local/share/applications/phone-mic-toggle.desktop
[ -f ~/.config/phone-mic/config ] || install -m 644 "$HERE/config.example" ~/.config/phone-mic/config

# Optional GNOME Quick Settings tile (only if GNOME Shell is present)
UUID=phone-mic@sharjeelmazhar.github.io
if command -v gnome-shell >/dev/null; then
    mkdir -p ~/.local/share/gnome-shell/extensions/$UUID
    cp -r "$HERE"/gnome-extension/$UUID/. ~/.local/share/gnome-shell/extensions/$UUID/
fi

# autostart, start now, enable the tile, KDE Connect buttons: the same step the .deb runs for each user
~/.local/bin/phone-mic setup
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) echo "Note: ~/.local/bin is not in PATH yet (it will be after the next login). Until then use: ~/.local/bin/phone-mic";; esac
