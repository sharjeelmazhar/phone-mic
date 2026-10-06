# Setup log

## 2026-10-06 — v2.1.2: fixes from the recorded reinstall test (Claude Code)

From the screen recordings of a full uninstall, reboot and reinstall, plus a router reboot and a Wi-Fi switch:
- The app screen did not update by itself (permission granted, paired, streaming started or stopped): the cards took a
  once-a-second tick as an unused parameter, and Compose skips a composable whose parameters are unused. They read it now.
- "Last connected 3 minutes ago" seconds after the stream dropped: the time was only stored when the stream started.
- QR pairing and first pairing did nothing for minutes before a reboot, with nothing in the computer's log: the computer
  never reached the phone (KDE Connect lost it too). The app now pings the computer and says at once when it gets no
  answer (router keeping that Wi-Fi apart), shows "Pairing with …" after a scan, and says when the computer answers but its
  Phone Mic is off.
- The tile kept saying "Streaming" up to ~20 s after the stream dropped: the daemon writes "waiting" as soon as it ends.
- Scanning the app-download QR code in the app now says that it is the download link.

## 2026-10-06 — v2.1.1: tile after uninstall + reinstall (Claude Code)

`phone-mic uninstall` used `gnome-extensions disable`, which puts the tile on GNOME's disabled-extensions list; a later
install only added it to enabled-extensions, and the disabled list wins, so the tile never came back. Uninstall now just
takes it off the enabled list, and setup also takes it off the disabled list.

## 2026-10-06 — v2.1.0: always on, QR pairing, new look (Claude Code)

**Asked for:** keep working with the app closed and after reboots, no notification clutter (never on the lock screen),
Android's own mic indicator instead, a modern Material UI that follows the phone's theme (Samsung too), a clear app icon,
explain in the app why it does not connect, pairing by scanning a QR code.

**Findings on the Redmi Note 11 (HyperOS 1.0, Android 13)**
- HyperOS does not deliver BOOT_COMPLETED / MY_PACKAGE_REPLACED to an app without its *Autostart* switch (MIUI app-op 10008);
  starting activities from the background also needs its op 10021. Both can only be switched on by the person (adb cannot).
- Android gives the microphone only to a foreground service started while the app is in the foreground, so a restart from the
  background has to go through an activity: allowed with "Display over other apps".
- A foreground-service notification is never shown below "low" importance, so the app cannot hide its own icon. On Android 13+
  an app without the notification permission keeps its service running with no notification at all, and Android's green
  mic dot appears while it records.
- Sticky restart right after an app update arrived while the app was already starting the service from the foreground; the
  first version of the restart logic stopped the service there. Removed: a muted mic is caught by the silence check instead.

**Changes (app)**
- Jetpack Compose + Material 3 with the wallpaper colours (Android 12+, follows One UI / HyperOS theming), edge-to-edge with
  correct status-bar icon colours, a status card that says why it is not connected (off, no Wi-Fi, not paired, wrong key,
  waiting since / last connected), a *Finish setup* card with only the missing items (each opens the right settings page;
  Xiaomi's two switches are read directly), paired computers with last connection, rename, QR scanner (Google code scanner,
  no camera permission) and a `phonemic://pair` link so the camera app works too.
- Restarts itself: invisible LaunchActivity from the boot/update receiver and a 15-minute watchdog alarm; muted-mic detection
  (not during calls, at most every 10 minutes).
- Notifications: no permission asked on Android 13+; otherwise minimum importance, secret on the lock screen, one title.
- BUSY when already streaming to another paired computer (two computers no longer take the mic from each other).
- Announces itself directly to known computers too (routers that drop broadcasts between Wi-Fi bands).
- New icon (phone with microphone and sound waves, coral to pink), themed monochrome icon.

**Changes (computer):** `phone-mic pair` shows a QR code (`qrencode` is now a dependency) whose secret authenticates the key
exchange; while it is shown, phones that did not scan it answer NOQR quietly instead of popping up a code dialog.

- Router finding: on the 2.4 GHz SSID "M" the router (ZTE) drops all traffic between the phone and the wired computer
  (ping fails both ways, KDE Connect stalls too); on "M 5G" everything works. That is the router's AP/SSID isolation, not
  something an app can get around. Switching back to "M 5G": streaming again after 7 s by itself. The app now mentions
  this after a minute of waiting; README troubleshooting too.

**Tests:** daemon tests extended (QR pairing, NOQR before the scan, wrong/old QR secret, BUSY): all pass. On the phone:
update installs, QR pairing 1.3 s after the scan with no tap, real audio, notification channel and lock-screen state.

## 2026-10-06 — v2.0.0: the phone app, wireless only, no developer options (Claude Code)

**Problem:** version 1 used adb + scrcpy. After every phone reboot (and in practice after router restarts) the phone had to be
plugged in by cable once to switch adb-over-Wi-Fi back on, and USB debugging had to stay enabled, which banking apps refuse.
Wanted: GSConnect-like behaviour, nothing to do after a restart, developer options off.

**Decision:** a small Android app of our own (Kotlin, no libraries, 43 KB) instead of adb. Android only lets an app use the
microphone while it is in the foreground or runs a microphone foreground service started from the foreground; so the app is a
foreground service, and after a phone reboot it asks for one tap (notification) instead of starting muted.

**Changes**
- `android/`: the app. Listens on TCP 47630, announces itself on UDP 47631, pairing with Diffie-Hellman (RFC 3526 group 14)
  and a 6-digit code approved on the phone, HMAC challenge-response both ways on every connection, audio encrypted with an
  HMAC-SHA256 keystream, mic recorded only while a paired computer is connected, Wi-Fi lock, a muted-mic detector.
- `bin/phone-mic-daemon` (Python, stdlib only) replaces `phone-mic-stream`: finds the phone by announcements, its last address,
  GSConnect's peer and a scan of the local /24 (1.2 s), every 5 s while waiting; plays into `phone_mic_in` with `pw-cat` through
  a small pipe (bounded delay); keepalive each second, gives up on a silent phone after 4 s. Same safety watchdogs as before.
- Pairing rules: the first phone pairs without a command; afterwards only within 5 minutes of `phone-mic pair` (also for a phone
  that lost its pairing: anything on the network could claim its id). A wrong key never removes the real pairing.
- `adb-server.service` gone (setup/upgrade removes its autostart link); Phone Mic device is now mono; new commands `pair`,
  `phones`, `forget`, `app`; the tile shows the pairing code; the `.deb` no longer depends on adb/scrcpy; the release workflow
  checks the APK version and publishes `PhoneMic.apk` next to the apt repository.

**Tests:** `tests/test_daemon.py` (fake phone in the real PipeWire, 40 checks), app unit tests with the same vectors, the app in
the Android 15 emulator (UI, pairing dialog, service; the emulator's microphone could not be fed audio on this machine), then the
Redmi Note 11 over the real Wi-Fi: found automatically, paired, real speech arrives without gaps, phone Wi-Fi off/on → back in
10 s by itself, upgrade 1.0.1 → 2.0.0 via the `.deb`.

## 2026-10-05 — v1.0.1: works on either Wi-Fi band, Phone Mic stays the default input (Claude Code)

**Problem:** the router (PTCL ZTE F1611A) has the 2.4 GHz network as SSID1 and 5 GHz as SSID5. When the phone
switched to 5 GHz it kept its IP (192.168.1.6), but GSConnect lost it and Phone Mic sat on "Waiting for phone".
After plugging in a 3.5 mm headset and rebooting, Settings showed no Phone Mic input although the tile said Streaming.

**Findings:** unicast to the phone works from either band (ping, adb on 5555 and GSConnect on 1716 all answer).
GSConnect finds devices by UDP *broadcast*, which the router does not pass between SSID5 and the LAN ports.
Phone Mic uses unicast adb, so it notices the dead link and reconnects by itself.

**Changes**
- `phone-mic-stream`: `GSCONNECT_NUDGE=1` (default): when there is no GSConnect link to the phone, call GSConnect's
  `connect` action with `lan://<phone ip>:1716` (unicast identity), while waiting and every 15 s while streaming.
- `phone-mic-stream`: `KEEP_DEFAULT=1` (opt-in): while streaming, put Phone Mic back as default input when anything else takes it.
- `phone-mic-device.service`: `priority.session/driver=3000` on `phone_mic`, so WirePlumber prefers it over built-in/jack mics
  when no choice is stored or the stored one is missing.

## 2026-09-18 — initial setup (Claude Code)

**Goal:** use Redmi Note 11 (model 2201117TG, HyperOS 1.0.8.0, Android 13) as microphone for desktop MDPT
(Dell Precision 5820, Ubuntu 26.04.1, GNOME 50 / Wayland, PipeWire 1.6.2 + WirePlumber 0.5.13). PC on Ethernet
192.168.1.y, phone on same LAN via Wi-Fi. GSConnect already works (not used for this; it has no mic feature).

**Findings on the computer**
- Already installed: `scrcpy 3.3.4`, `adb 34.0.5`, `pw-loopback`, `pactl`. Not installed: ffmpeg (not needed).
- User `smr` is in `plugdev`; udev rule `51-android.rules` present → adb over USB works without sudo.
- No sudo available to Claude → everything done as user-level config. **No system files changed, no packages installed.**
- Audio before: default sink `alsa_output.pci-0000_00_1f.3.analog-stereo`, default source `alsa_input.pci-0000_00_1f.3.analog-stereo` (unchanged so far).

**Decision:** scrcpy mic capture over adb instead of a phone app (WO Mic, DroidCam, AndroidMic, AudioRelay…):
nothing to install/keep alive on HyperOS (which kills background apps aggressively), open source, already packaged in Ubuntu,
works over USB *and* Wi-Fi, low latency. Trade-off: needs USB debugging enabled on the phone, and Wi-Fi mode needs one USB plug after each phone reboot.

**Done**
1. Tested virtual device manually with `pw-loopback` (Audio/Sink `phone_mic_sink` → Audio/Source `phone_mic`).
   Test: 440 Hz tone at 0.5 volume played into `phone_mic_sink`, recorded from `phone_mic` → peak 16384/32767. ✔ Path works.
   Default sink/source were not hijacked by the new nodes. ✔
2. Wrote `bin/phone-mic-stream`, `bin/phone-mic`, two systemd user units, desktop launcher, `install.sh`, `uninstall.sh`.
3. Ran `./install.sh` → both services enabled + active; log shows `waiting for phone`.

**Pending**
- [x] Phone: Developer options + USB debugging enabled, USB authorised (serial <serial>, phone Wi-Fi IP 192.168.1.x).
- [x] Stream over USB verified (04:27): 2 s recording from default input → peak 1292, rms 326 (room noise), not silent.
- [x] Wi-Fi mode verified 04:31 after unplugging (user confirmed dictation works; log: `phone found over Wi-Fi (192.168.1.x:5555)`).
- [x] Phone Mic set as default input (`pactl set-default-source phone_mic`).

### 04:20–04:27 — first connection, two problems found and fixed
- Phone connected fine on first try; script auto-enabled `adb tcpip 5555` and saved `192.168.1.x:5555`.
  (The one "Device disconnected / exit 2" in the log right after is expected: enabling tcpip restarts adb on the phone; service retried by itself.)
- **Problem 1: phone mic was audible on the PC speakers.** Cause: in Settings → Sound the *Output Device* was switched
  (to "Phone Mic (internal input)" and back). GNOME moves *all* playing streams to the newly chosen output, so it dragged the
  scrcpy stream from `phone_mic_sink` onto the speakers. A fresh start routed correctly.
  **Fix:** stream now carries `node.dont-move=true node.dont-fallback=true` (via `PULSE_PROP` in `bin/phone-mic-stream`).
  Verified: `pactl move-sink-input <id> <speakers>` no longer moves it. If the virtual sink is ever missing, the stream stays
  unconnected instead of falling back to the speakers.
- **Problem 2: Settings showed "No Input Devices".** The default input had fallen back to the *speaker monitor* because the earphones
  were unplugged. Probed GNOME's own device library (Gvc via gjs): it *does* list input "Phone Mic" → Settings window was stale.
  Set default input to `phone_mic`. Reopen Settings → Sound to see it.
- **Rule:** never pick "Phone Mic (internal input)" as *Output* device. It is only the pipe the phone audio flows through. Output stays "Speakers".

### 04:45–05:10 — redesign: no more "Phone Mic (internal input)" under Output
- **User report:** Output list showed "Phone Mic (internal input)"; it even became the default output (plugging headphones
  left output on it, had to switch manually). Cause: v1 used a virtual *sink* (`phone_mic_sink`) as entry point, and every sink is an output device for GNOME.
- Reset default output to `alsa_output.pci-0000_00_1f.3.analog-stereo` (Speakers/Headphones jack; ALSA switches the port itself).
- **Experiments** (all in scratch, test nodes removed afterwards):
  1. `Audio/Source/Virtual` null node + `target.object`: WirePlumber does *not* link a playback stream to it → fell back to speakers (user heard a test beep, sorry). ✘
  2. pw-loopback whose capture side is a plain stream (`node.autoconnect=false`, no media.class) + native PipeWire player with `--target`: linked, audio OK, **no sink created**. ✔
  3. Same via PulseAudio API (`PULSE_PROP target.object=`): link stays `[paused]`, silence. ✘ → scrcpy must use SDL's native PipeWire driver.
  4. scrcpy with `SDL_AUDIODRIVER=pipewire PIPEWIRE_NODE=<stream>`: linked `[active]`, peak 6852. ✔
- **New design (v2):** `phone-mic-device.service` creates `phone_mic_in` (stream, invisible in Settings) → `phone_mic` (source).
  `phone-mic-stream` runs scrcpy with `SDL_AUDIODRIVER=pipewire PIPEWIRE_NODE=phone_mic_in` and
  `PIPEWIRE_PROPS={ node.dont-move=true node.dont-fallback=true }`.
- **Tests after install:** sinks = only the 2 real ones ✔ · real audio peak 18254 ✔ · stopped virtual mic while streaming → stream had *no* links (not on speakers) ✔ ·
  but after the virtual mic came back the stream did **not** re-link ✘ → added **watchdog** in `phone-mic-stream` (checks link every 5 s, restarts scrcpy). Re-test: self-healed in <30 s, peak 19456 ✔
- **adb-server.service added:** previously the adb server was a child of phone-mic.service, so every restart of the mic would have killed
  other adb users (e.g. a scrcpy mirror window). Now it is its own unit.
- **scrcpy mirror + mic simultaneously over Wi-Fi:** `scrcpy --no-audio --no-window --record … --time-limit=5` produced 2.5 MB video, mic had 0 restarts, peak 19579 ✔
  (The `MediaCodec Error 0xfffffff4` printed at the end of that test is just the encoder being stopped by `--time-limit`.)
- Phone Developer options reviewed (screenshot 04:44): USB debugging ON, Security settings ON, adb authorisation timeout disabled ON, Install via USB OFF → all fine, nothing to turn off (see README table).
- Folder put under git.

**Still open**
- [x] Computer reboot (05:25): all three services up, phone found over Wi-Fi with no cable, speakers + Phone Mic confirmed by user.
- [ ] Behaviour with phone screen off / locked for >10 min (not observed yet; streaming with screen off worked for shorter periods).

### 05:15–05:40 — polish for public repo
- `phone-mic enable|disable` added (autostart on/off); `phone-mic off` now leaves the unit *inactive* instead of *failed* (clean exit on SIGTERM).
- `PHONE_SERIAL` config option (lock to one phone when several Android devices are plugged in).
- Stale Wi-Fi adb connection is dropped when a Wi-Fi session dies within 20 s (suspend/resume case). Not yet tested with a real suspend.
- Identifiers (serial, IPs) scrubbed from this log for publishing. README rewritten for fresh installs and other users.
- Tested: off → inactive, on → streaming again within ~8 s.

### 05:40–06:05 — regression caught, second safety guard
- **Regression (my fault):** while adding a timestamp I broke the line continuation before `scrcpy`, so it started *without*
  `PIPEWIRE_NODE`/`PIPEWIRE_PROPS` and WirePlumber sent the phone mic to the speakers for ~15 min. Fixed the line order.
- **New guard in `phone-mic-stream`:** the stream node is now named `phone_mic_stream`; every second the script checks
  its links and kills scrcpy at once if it is linked to anything except `phone_mic_in` (log: `SAFETY STOP`).
  Test: forced a link to the speakers with `pw-link` → stopped within 1 s, back on the virtual mic only after 3 s ✔
- **Observed:** with the phone screen off and no active stream, HyperOS Wi-Fi power-save made the phone unreachable for ~30 s
  (ping loss, port 5555 closed), then it came back and the service reconnected alone. So a reconnect can take up to a minute
  when the phone is asleep; while streaming, the link stays up. Documented in README.

### 05:36–05:40 — full power-off test (router + computer + phone all off, then on)
Timeline from the journal:
- 05:36:36 computer up, services started, `waiting for phone` — phone still off / just booting. As expected, no stream:
  the phone reboot forgot adb-over-Wi-Fi.
- 05:39:16 cable plugged in → `phone found over USB`, adb-over-Wi-Fi re-enabled, **new phone IP learned (192.168.1.x → 192.168.1.z,
  the router handed out a different address after its restart)**.
- 05:39:20 streaming over USB.
- 05:40:01 cable removed → scrcpy ended; 05:40:05 `phone found over Wi-Fi` at the new address, streaming again. 4 s gap.
Result: ✔ worst case handled with a single ~40 s cable plug; IP change handled automatically. User confirmed mic works.
- Headphones test (user, 05:30): plugging wired headphones moves output to headphones, input stays Phone Mic; switching input
  to the headset mic and back works; unplugging returns output to speakers and input to Phone Mic. ✔

### 05:45–05:55 — GNOME Quick Settings tile
- User asked for an on/off toggle in the top-right quick menu. That needs a GNOME Shell extension; written as
  `gnome-extension/phone-mic@sharjeelmazhar.github.io/` (metadata.json + extension.js, ~120 lines, no settings schema, no system changes).
  Tile: title "Phone Mic", subtitle Off / Waiting for phone / Streaming, click runs `phone-mic on|off`; panel icon `phone-symbolic` while streaming.
  Polls `phone-mic state` every 5 s and on menu open (Gio.Subprocess, async, never blocks the shell).
- `phone-mic state` subcommand added. `install.sh`/`uninstall.sh` copy/remove and enable/disable the extension when `gnome-shell` exists.
- Verified logic outside the shell with gjs and stubbed shell classes: state read correctly, tile checked, indicator visible.
  Not yet seen live: GNOME (Wayland) loads new extensions only at login → user must log out/in once.

### 06:00–06:20 — review of the whole repo before "fresh install" use
- Read every file top to bottom. Running code is correct. Four fresh-install / other-user gaps fixed:
  1. Tile had to be enabled by hand in Extension Manager (`gnome-extensions enable` fails before the shell has loaded the extension).
     `install.sh` now also writes the UUID into `org.gnome.shell enabled-extensions` → enabled at next login automatically.
  2. Right after a first install `phone-mic` is not on PATH until re-login (Ubuntu adds `~/.local/bin` at login only if it exists).
     Services, tile and launcher use full paths so they don't care; `install.sh` prints a hint.
  3. Phone IP detection was hard-wired to `wlan0`; now any `wlan*` interface.
  4. Opt-in `AUTO_DEFAULT=1`: Phone Mic becomes default input only while streaming; previous input restored afterwards (laptops).
     Default 0, so this desktop's behaviour is unchanged. Tested `take_default`/`restore_default` in isolation with the speaker monitor
     as stand-in previous input: switched, remembered, restored; no-op when Phone Mic already was the default ✔.
     The integrated path (called when the stream links) is not yet observed live — the phone was unreachable on Wi-Fi during the test.
- Observed again: the phone dropped off Wi-Fi (no ping) for several minutes while idle; service kept waiting as designed.
- User confirmed: after reboot the tile appears in Quick Settings and toggles the mic.

### 06:20–06:35 — phone Wi-Fi off was not detected
- **User report:** phone Wi-Fi switched off → tile kept saying *Streaming*; only `off`/`on` got it back to *Waiting for phone*.
  Cause: a dead TCP connection is invisible to adb and scrcpy (no keepalive); scrcpy blocks on read forever and the PipeWire
  stream stays linked, so `phone-mic state` truthfully reported "streaming" (link exists) while no audio arrived.
- The "turned off by itself" from the same test: journal shows two stops at 06:21:07 and 06:21:10 = clicks on the tile while
  it was stuck showing *Streaming* (so a click meant "off"). Not a bug in the stop logic; fixed by making the state truthful.
- **Fix in `phone-mic-stream`:** `alive()` = `timeout 5 adb -s <ip:5555> shell true`. Checked once before starting scrcpy
  (a dead adb entry is dropped immediately instead of `adb push` hanging 12 s) and every 4 s while streaming (3 s timeout); on failure the
  stream is stopped, the stale adb connection dropped, and the normal reconnect loop takes over (~7 s to notice). USB sessions
  are unaffected (USB unplug is detected instantly anyway).
- Tile poll interval 5 s → 3 s (takes effect after the next login).
- Verified: no false restarts while streaming normally over Wi-Fi. Not yet verified with the phone's Wi-Fi actually off — user to test.

### 06:33–06:40 — timed Wi-Fi off/on tests, restart-gap display fix
- Two timed rounds with a 1 s state watch. Phone Wi-Fi off → *waiting* in ~7 s (incl. user reaction time); Wi-Fi on → *streaming*
  in 9–16 s (phone re-joining Wi-Fi + 4 s retry rhythm). Liveness check: every 4 s, 3 s timeout.
- The watch exposed a cosmetic bug: for the 3 s systemd restart gap after a reconnect, `phone-mic state` said *off*
  (unit ActiveState=activating). The tile therefore flashed "Off" — very likely the earlier "it turned off by itself" report.
  Fix: `state` treats active/activating/reloading as running; only inactive/failed is *off*. Verified by killing scrcpy: no *off* during the gap.

### 06:40–06:41 — second full power-off test (router + computer + phone), timed with a 1 s watch
- 06:40:46 computer up, *waiting*, no phone (phone rebooted → Wi-Fi debugging forgotten, as expected).
- 06:40:52 cable in → phone on USB; 06:40:54 one-second drop (adbd restarting for `tcpip 5555`); 06:41:00 **streaming over USB** (8 s after plug-in).
- 06:41:07 cable out; 06:41:11 found over Wi-Fi; 06:41:13 **streaming over Wi-Fi** (6 s after unplug).
Result ✔ — identical to the first full test, now with exact timings.

### 06:50 — own top-bar icon
- The top-bar indicator used Adwaita's stock `phone-symbolic`. Replaced with an own 16 px symbolic SVG
  (`gnome-extension/…/icons/phone-mic-symbolic.svg`): phone frame with a microphone inside, fills only (shell recolours it).
  Loaded via `Gio.icon_new_for_string(extension.path + '/icons/…')`. `install.sh` now copies the extension directory recursively.
  Rendered at 160 px and at 16 px for a visual check. Visible after the next login (GNOME reloads extensions only at login).

### 07:00 — final touches
- README: framed for any Linux computer (desktop or laptop, with or without a mic), GNOME-only note for the tile, `AUTO_DEFAULT` in the
  options list, Wi-Fi off/on timings in the Tested table, liveness check in "How it works", tile in Uninstall.
- MIT LICENSE added. GitHub repository description and topics set.
- Compatibility section added: honest about what was tested (Ubuntu 26.04 + GNOME only) vs. what should work (PipeWire + systemd distros,
  scrcpy >= 2.1) vs. what will not (plain PulseAudio, non-systemd, Debian 12 scrcpy 1.25, non-GNOME has no tile). `install.sh` now prints
  the right package command for apt / pacman / dnf, refuses scrcpy < 2.1, and warns if the audio server is not PipeWire.
- Full review of all scripts/units done earlier today (06:00); no code changes in this commit.

### 07:15–07:40 — control the mic from the phone (KDE Connect "Run Command")
- Manually added three entries to GSConnect's run-command list for the paired phone via `dconf write`
  (key `…/device/<id>/plugin/runcommand/command-list`, type `a{sv}` of `a{ss}` {name, command}); user confirmed they appear in the
  KDE Connect app and in Android's quick-settings KDE Connect tile, and that OFF/ON work from the phone.
- Productised as `bin/phone-mic-gsconnect.js` (gjs, uses GSConnect's own GSettings schemas from the user or system extension dir,
  `recursiveUnpack` → merge → `GLib.Variant('a{sv}')`) driven by `phone-mic kdeconnect [add|remove]`. Absolute path to `phone-mic`
  is stored because GSConnect runs commands with a minimal PATH. `install.sh` calls `add` (non-fatal), `uninstall.sh` calls `remove`.
- Edge cases tested: remove (ours gone, GSConnect defaults kept) · add (3 added, total 8) · add again (still 8, no duplicates) ·
  key reset to defaults then add (defaults + ours) · GSConnect absent (simulated via env var: exit 2, instructions printed, nothing
  changed) · install.sh end to end · stored command paths. Unpaired-device branch not exercised (needs a second, unpaired phone).
- KDE Plasma's KDE Connect is not automated (no way to test here); the command prints the exact lines to add by hand.
- README: section + two screenshots (docs/kdeconnect-*.jpg), Tested table row, uninstall note.

### 07:50 — `phone-mic uninstall`
- Installed files never reference the source folder (verified by grep over everything installed + the GSConnect entries), so the
  folder can be deleted after `./install.sh`. To make removal possible without re-cloning, `phone-mic uninstall` was added;
  `uninstall.sh` now just calls it. It also resets the default input if it was Phone Mic.
- Real test on this machine: uninstall → no files, units, tile, launcher, GSConnect entries or virtual mic left; reinstall →
  everything back, GSConnect buttons re-added, streaming after 1 s.

### 08:00 — uninstall audited against everything install creates or changes
- Gaps found and closed: `~/.config/phone-mic` was kept (now removed); the UUID stayed in `enabled-extensions` if the shell had
  never loaded the tile (now removed from the list directly); GSConnect key stayed explicitly set (now reset when equal to the schema
  default); default-input reset used an invalid name (now the first real mic); phone stayed in adb-tcpip mode (now `adb usb` on every
  reachable phone + `adb disconnect`).
- Real uninstall on this machine, checked: files 0, autostart links 0, units 0, extension dir 0, enabled-extensions 0, config dir 0,
  GSConnect key unset, virtual mic 0, adb devices 0, phone port 5555 closed. Then reinstalled; config restored from a backup taken before
  the test. Because `adb usb` ran, Wi-Fi mode needs the one-time USB plug again.
- Leftovers that are deliberately not ours to delete are listed in the README (adb key, phone Developer options, journal).

## 2026-10-01 — Debian package and apt repository (Claude Code)

**Goal:** install with one `.deb` and get later versions through `sudo apt update && sudo apt upgrade`, the same way as the
Time Tracker app, instead of cloning the repo and running `install.sh`.

**Design**
- A `.deb` installs for the whole computer as root; the mic is per user (systemd *user* services, the tile in each user's
  GNOME settings). So the files moved to system paths (`/usr/bin/phone-mic`, `/usr/lib/phone-mic/`, `/usr/lib/systemd/user/`,
  `/usr/share/gnome-shell/extensions/`) and the per-user half of the old `install.sh` became `phone-mic setup`.
  The package's `postinst` runs it for everyone who is logged in (`packaging/each-user`: `runuser` with a clean environment and
  the user's `XDG_RUNTIME_DIR` / session bus), and `phone-mic-setup.service` (shipped enabled in `default.target.wants`,
  skipped once `~/.config/phone-mic/setup-done` exists) runs it for other users at their next login.
- Autostart stays a per-user `systemctl --user enable`, not a global one, so `phone-mic enable|disable` keep working as before.
- `phone-mic setup` removes a copy installed earlier by `install.sh` (it would shadow the packaged units and tile), keeping
  config, phone address and an earlier `phone-mic disable`. The adb server is left running during that.
- Upgrade: `postinst` reloads the user managers and `try-restart`s the device and stream units, so a running mic continues
  with the new scripts. `apt remove`: `prerm` undoes the per-user setup for logged-in users, config kept. `apt purge` /
  `phone-mic uninstall`: also `~/.config/phone-mic` and the apt source.
- `install.sh` stays for distros without apt; the repo files now carry the package's paths and `install.sh` rewrites them
  to the home folder. `bin/phone-mic` works in both layouts (helpers next to it, or in `/usr/lib/phone-mic`).
- apt repository: flat repository on GitHub Pages, built and signed in the release workflow on every `v*` tag; the `.deb`
  ships `/etc/apt/sources.list.d/phone-mic.sources` and the public key. The workflow refuses a tag that does not match
  `VERSION=` in `bin/phone-mic`.

**Found on the way**
- Only Ubuntu 26.04 ships a scrcpy that can capture the mic (3.3.4). 24.04, 25.04 and 25.10 all have 1.25 (checked on
  Launchpad), so the README's "Ubuntu 24.04+" was wrong. scrcpy is therefore only *recommended* by the package: on older
  releases it still installs, and `phone-mic status` / the service log print a `PROBLEM:` line until a newer scrcpy is there.
  The service then looks again every 5 minutes instead of failing every 3 seconds.
- `pipewire-pulse` and `wireplumber` had to become hard dependencies: as recommendations apt configured them *after*
  phone-mic's own setup ran, so on a system without them the first start came too early.
- The tile's `shell-version` list now covers 45–51. 46 and 50 were tried (below); 51 is listed unseen.

**Tests** (QEMU VMs from the Ubuntu cloud images; no phone attached, so everything up to *Waiting for phone*)
- 26.04: `apt install ./phone-mic.deb` on a clean system → dependencies pulled in, three services active and enabled for the
  logged-in user, virtual mic present, state *waiting* ✔ · second user's first login → set up by `phone-mic-setup.service` ✔ ·
  1.0.1 in a signed test repository → `apt update` verifies it with the shipped key, `apt upgrade` installs it, stream service
  restarted (new PID) ✔ · `phone-mic disable` stays off across an upgrade and a repeated `phone-mic setup` ✔ ·
  `apt remove` → services, autostart links, virtual mic and adb server gone, config kept; reinstall → back, config still there ✔ ·
  `phone-mic uninstall` → package purged, nothing left for either user ✔.
- 26.04, old `install.sh` (commit bb0514e) then the `.deb`: home-folder scripts, units and launcher gone, units now loaded from
  `/usr/lib/systemd/user`, config + phone address kept, adb server PID unchanged ✔. Same with the new `install.sh` ✔;
  `install.sh` with the package present refuses politely ✔; home-folder install + uninstall still clean ✔.
- Tile: with a headless GNOME Shell running, `apt install` wrote the UUID into `enabled-extensions` through the package script;
  after a shell restart the extension is loaded from `/usr/share/gnome-shell/extensions` and *ACTIVE* with no JS errors, on
  GNOME 50 (26.04) and GNOME 46 (24.04). Not seen: the tile itself on 46 (headless shell has no screen).
- 24.04: installs without scrcpy (recommendation not satisfiable), `PROBLEM: scrcpy is not installed…` in status and log ✔.
- Not tested: streaming from a phone with the packaged version; the tile on screen on GNOME 46; Debian.

### 14:05 UTC — first release, v1.0.0
- Repo side set up once: `APT_SIGNING_KEY` secret, GitHub Pages with "GitHub Actions" as source, and the `github-pages`
  environment allowed to deploy from `main` and from `v*` tags (by default only the default branch may deploy).
- The first push of the tag (together with the new branch that introduced the workflow, during a GitHub incident) created
  no workflow run at all. Pushing the same tag again started it. If a tag push ever shows nothing under Actions: delete the
  tag on GitHub and push it again.
- Checked against the published files in both VMs: `wget …/releases/latest/download/phone-mic.deb`, `sudo apt install ./phone-mic.deb`,
  then `sudo apt update` fetches `InRelease` and `Packages` from `https://sharjeelmazhar.github.io/phone-mic` without warnings and
  `apt policy phone-mic` lists that repository as the source ✔.
