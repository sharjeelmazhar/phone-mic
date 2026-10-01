#!/usr/bin/env bash
# Removes everything the installation put in place. Same as:  phone-mic uninstall   (which works without this folder)
if [ -x ~/.local/bin/phone-mic ]; then
    exec ~/.local/bin/phone-mic uninstall
elif [ -x /usr/bin/phone-mic ]; then
    exec /usr/bin/phone-mic uninstall
else
    echo "phone-mic is not installed."
fi
