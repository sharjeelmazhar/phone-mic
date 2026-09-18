# Setup log

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
- [ ] User: reboot computer and confirm everything comes up alone.
- [ ] Behaviour with phone screen off / locked for >10 min.

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
