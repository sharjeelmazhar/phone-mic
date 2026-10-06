#!/bin/sh
# Build a signed apt repository holding dist/phone-mic_<version>_all.deb in ./site.
# The release workflow publishes it on GitHub Pages; the .deb installs a source entry
# pointing there, so "sudo apt upgrade" picks up new versions.
# Signs with the secret key in gpg's keyring (in CI: the APT_SIGNING_KEY secret).
set -eu
cd "$(dirname "$(readlink -f "$0")")"

rm -rf site
mkdir site
cp dist/phone-mic_*_all.deb site/
# the phone app, at a link that never changes (shown by `phone-mic app` and in the README)
cp PhoneMic.apk site/
cd site

dpkg-scanpackages --multiversion . > Packages 2>/dev/null
gzip -9 -k Packages

# apt checks the package list against these sizes and hashes, and the signature covers them
{
    echo "Origin: Phone Mic"
    echo "Label: Phone Mic"
    echo "Suite: stable"
    echo "Date: $(LC_ALL=C date -u '+%a, %d %b %Y %H:%M:%S UTC')"
    echo "SHA256:"
    for file in Packages Packages.gz; do
        echo " $(sha256sum "$file" | cut -d' ' -f1) $(stat -c %s "$file") $file"
    done
} > Release

KEY=$(gpg --show-keys --with-colons ../packaging/phone-mic-archive-keyring.gpg | awk -F: '$1=="fpr"{print $10; exit}')
gpg --batch --yes --local-user "$KEY" --clearsign --output InRelease Release
gpg --batch --yes --local-user "$KEY" --armor --detach-sign --output Release.gpg Release

cat > index.html <<'HTML'
<!doctype html>
<meta charset="utf-8">
<title>Phone Mic apt repository</title>
<p>This is the apt repository of <a href="https://github.com/sharjeelmazhar/phone-mic">Phone Mic</a>.
Install it from its <a href="https://github.com/sharjeelmazhar/phone-mic/releases/latest">latest release</a>;
that adds this repository so that updates arrive with <code>sudo apt upgrade</code>.</p>
<p>The phone app: <a href="PhoneMic.apk">PhoneMic.apk</a>.</p>
HTML
