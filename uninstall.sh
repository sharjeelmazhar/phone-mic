#!/usr/bin/env bash
# Removes everything install.sh put in place. Nothing system-wide was ever changed.
systemctl --user disable --now phone-mic.service phone-mic-device.service adb-server.service 2>/dev/null
rm -f ~/.config/systemd/user/adb-server.service ~/.config/systemd/user/phone-mic.service ~/.config/systemd/user/phone-mic-device.service
rm -f ~/.local/bin/phone-mic ~/.local/bin/phone-mic-stream ~/.local/share/applications/phone-mic-toggle.desktop
systemctl --user daemon-reload
echo "Removed. (Kept ~/.config/phone-mic/ — delete it by hand if you want.)"
echo "On the phone you can now switch off Developer options > USB debugging."
