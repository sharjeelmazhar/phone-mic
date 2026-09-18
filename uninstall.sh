#!/usr/bin/env bash
# Removes everything install.sh put in place. Same as:  phone-mic uninstall   (which works without this folder)
if [ -x ~/.local/bin/phone-mic ]; then
    exec ~/.local/bin/phone-mic uninstall
else
    echo "phone-mic is not installed (nothing in ~/.local/bin)."
fi
