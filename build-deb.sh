#!/bin/sh
# Build dist/phone-mic_<version>_all.deb. Needs nothing beyond dpkg-deb, which every Ubuntu has.
set -eu
cd "$(dirname "$(readlink -f "$0")")"

PKG=phone-mic
UUID=phone-mic@sharjeelmazhar.github.io
VERSION=$(sed -n 's/^VERSION=\([0-9][0-9.]*\)$/\1/p' bin/phone-mic)
[ -n "$VERSION" ] || { echo "could not read VERSION from bin/phone-mic" >&2; exit 1; }

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
ROOT=$STAGE/root

install -Dm755 bin/phone-mic "$ROOT/usr/bin/phone-mic"
install -Dm755 -t "$ROOT/usr/lib/$PKG" bin/phone-mic-stream bin/phone-mic-gsconnect.js packaging/each-user
install -Dm644 -t "$ROOT/usr/lib/systemd/user" systemd/*.service packaging/phone-mic-setup.service
# started in every user's session; it does something only for a user who has not been set up yet
install -d "$ROOT/usr/lib/systemd/user/default.target.wants"
ln -s ../phone-mic-setup.service "$ROOT/usr/lib/systemd/user/default.target.wants/phone-mic-setup.service"
install -Dm644 -t "$ROOT/usr/share/applications" phone-mic-toggle.desktop
install -Dm644 -t "$ROOT/usr/share/gnome-shell/extensions/$UUID" "gnome-extension/$UUID/extension.js" "gnome-extension/$UUID/metadata.json"
install -Dm644 -t "$ROOT/usr/share/gnome-shell/extensions/$UUID/icons" "gnome-extension/$UUID/icons/phone-mic-symbolic.svg"
install -Dm644 -t "$ROOT/usr/share/$PKG" config.example
install -Dm644 LICENSE "$ROOT/usr/share/doc/$PKG/copyright"
# the package's own apt repository, so that "apt upgrade" brings new versions
install -Dm644 -t "$ROOT/usr/share/keyrings" packaging/phone-mic-archive-keyring.gpg
install -Dm644 -t "$ROOT/etc/apt/sources.list.d" packaging/phone-mic.sources

install -d "$ROOT/DEBIAN"
install -m755 packaging/postinst packaging/prerm packaging/postrm "$ROOT/DEBIAN/"
# scrcpy is only recommended: Ubuntu before 26.04 ships 1.25, too old for the mic. There the package still
# installs and `phone-mic status` says what to do; a newer scrcpy in /usr/local/bin is picked up.
cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: $PKG
Version: $VERSION
Section: sound
Priority: optional
Architecture: all
Depends: adb, pipewire-bin, pipewire-pulse, wireplumber, pulseaudio-utils, python3
Recommends: scrcpy (>= 2.1), libnotify-bin
Suggests: gnome-shell (>= 45), gnome-shell-extension-gsconnect, gjs
Installed-Size: $(du -sk --exclude=DEBIAN "$ROOT" | cut -f1)
Maintainer: Sharjeel M. Rajput <sharjeelmazhar@gmail.com>
Homepage: https://github.com/sharjeelmazhar/phone-mic
Description: Use an Android phone as this computer's microphone
 The phone shows up as an input device called "Phone Mic" in every app,
 over a USB cable or Wi-Fi, with nothing to install on the phone (it uses
 adb and scrcpy). It starts at login and reconnects by itself when the
 phone comes and goes. On GNOME a Quick Settings tile shows the state and
 switches the microphone on and off.
CONTROL
echo /etc/apt/sources.list.d/phone-mic.sources > "$ROOT/DEBIAN/conffiles"

mkdir -p dist
dpkg-deb --root-owner-group --build "$ROOT" "dist/${PKG}_${VERSION}_all.deb"
