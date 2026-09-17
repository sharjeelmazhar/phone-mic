# Phone as microphone (Redmi Note 11 → Ubuntu 26.04 desktop "MDPT")

The phone's microphone shows up on the computer as a normal input device called **"Phone Mic"**.
Any app (dictation, WhatsApp Web/Desktop, browsers, recorders) can use it.

## How it works

```
Phone mic ──(adb: USB cable or Wi-Fi)──> scrcpy ──> phone_mic_in ──(pw-loopback)──> "Phone Mic" input device ──> apps
```

- **No app is installed on the phone.** `scrcpy` (already in Ubuntu, open source) uses Android's built-in
  debugging link (adb) and captures the mic (`--audio-source=mic`, needs Android 11+, phone has 13).
- Three systemd *user* services (no sudo, nothing system-wide was changed):
  - `adb-server.service` — the adb server in its own unit, shared with any `scrcpy`/`adb` you run yourself.
  - `phone-mic-device.service` — creates the virtual "Phone Mic" *input* device with `pw-loopback`. Always there, even with no phone.
    It deliberately creates **no output device**: Settings → Sound → Output only ever shows real speakers/headphones.
  - `phone-mic.service` — runs `phone-mic-stream`: waits for the phone, streams, and automatically retries forever when the link drops.
- A watchdog in `phone-mic-stream` restarts the stream if it ever loses its connection to the virtual mic (e.g. PipeWire restarted).
- All start automatically at login. Nothing to do after a reboot of the computer.

## Phone setup (one time)

1. **Settings → About phone → tap "OS version" 7 times** → "You are now a developer".
2. **Settings → Additional settings → Developer options**:
   - turn on **USB debugging** (accept the warnings),
   - if the entry exists, turn on **Disable adb authorization timeout** (otherwise the phone forgets the computer after 7 days without use).
   - *Not* needed: "USB debugging (Security settings)", "Install via USB", Mi account.
3. Plug the phone into the computer with a **data-capable USB cable**, unlock the phone.
4. A prompt "Allow USB debugging?" appears → tick **Always allow from this computer** → **Allow**.
5. Within a few seconds the mic streams. Verify on the computer: `phone-mic status` then `phone-mic test`.

## Daily use

| Situation | What to do |
|---|---|
| Phone plugged in by USB | Nothing. Streams automatically (most reliable, also charges). |
| Phone on Wi-Fi only | Nothing, as long as the phone was on USB once since its last **phone** reboot (see below). |
| Phone was rebooted | Plug it into USB once for ~5 seconds. That re-enables Wi-Fi mode. |
| Want the mic off (privacy/battery) | App grid → **"Phone Mic (on/off)"**, or `phone-mic off` / `phone-mic on`. |

Why the USB-once rule: Wi-Fi mode uses `adb tcpip 5555`, which Android forgets on every phone reboot.
The script re-enables it and re-learns the phone's IP address automatically every time it sees the phone on USB.
Android shows a green mic indicator while streaming — that is expected.

## Using scrcpy (screen mirroring) at the same time

Works, also wirelessly, and does not disturb the mic (tested: mirror + mic together over Wi-Fi).

```
scrcpy              # when the phone is only on Wi-Fi OR only on USB
scrcpy -e           # pick the Wi-Fi connection when the phone is also plugged in
scrcpy -d           # pick the USB connection when both exist (smoothest video)
```

Phone Developer options needed:

| Setting | Needed for |
|---|---|
| USB debugging = ON | everything (mic + scrcpy) |
| Disable adb authorisation timeout = ON | not having to re-authorise after a week |
| USB debugging (Security settings) = ON | only for *controlling* the phone with mouse/keyboard in scrcpy. Mic and view-only mirroring work without it. |
| Install via USB, Wireless debugging | not needed (leave off) |

## Fresh install on a new / reinstalled system

```
sudo apt install adb scrcpy pipewire-bin pulseaudio-utils git
git clone <your repo url> ~/Code/Mic-Setup && cd ~/Code/Mic-Setup
./install.sh
```
Then plug the phone in by USB once and accept "Allow USB debugging?" with **Always allow** ticked
(a reinstalled computer has a new adb key, so the phone asks again). Finally: `phone-mic default`.
Requires PipeWire + WirePlumber (default on Ubuntu 22.10+).

## Commands (`~/.local/bin/phone-mic`)

```
phone-mic status    # services, adb devices, default input, streaming or not
phone-mic test      # record 5 s from Phone Mic, show level, play it back
phone-mic on|off|toggle
phone-mic default   # make Phone Mic the default input device
phone-mic log       # recent log lines
phone-mic doctor    # status + log, paste this when asking for help
```

Settings: `~/.config/phone-mic/config` (mic mode, buffer, Wi-Fi on/off, fixed IP). See `config.example`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `adb devices` shows nothing on USB | Charge-only cable or bad port. Try another cable. Check USB debugging is on. |
| `unauthorized` in `adb devices` / log | Unlock phone, accept the prompt. No prompt? Developer options → *Revoke USB debugging authorizations*, replug. |
| Works on USB, not on Wi-Fi | Phone rebooted (plug USB once), or phone IP changed (plug USB once), or phone left Wi-Fi. Optional: reserve a fixed IP for the phone in the router. |
| Crackling / dropouts on Wi-Fi | Set `AUDIO_BUFFER=150` in the config. Or use USB. |
| App doesn't hear anything | Choose **Phone Mic** as input in the app or in Settings → Sound → Input (`phone-mic default`). Check input volume is not 0. Run `phone-mic test`. |
| Phone audio comes out of the PC speakers | Should not happen: the stream is pinned (`node.dont-move`, `node.dont-fallback`) and tested to stay unlinked rather than fall back to speakers. `phone-mic off; phone-mic on`, then check `phone-mic log`. |
| "Phone Mic" device missing | `systemctl --user status phone-mic-device` ; `systemctl --user restart phone-mic-device`. |
| Stream stops after several minutes with screen off | HyperOS battery saver. Keep phone charging, or Developer options → "Stay awake" while charging. Tell Claude what `phone-mic log` says. |
| Anything else | `phone-mic doctor` and read the log. Manual run for full output: `phone-mic off; ~/.local/bin/phone-mic-stream` |

## Security notes

- USB debugging lets an *authorized* computer control the phone. Only this computer's key (`~/.android/adbkey`) is authorized. Never accept the debugging prompt for a computer you don't know.
- Wi-Fi mode opens adb port 5555 on the phone inside the home network; connections still need the authorized key.
  To avoid it completely set `WIFI=0` in the config (USB only).

## Files

| In this folder | Installed to |
|---|---|
| `bin/phone-mic`, `bin/phone-mic-stream` | `~/.local/bin/` |
| `systemd/*.service` | `~/.config/systemd/user/` |
| `phone-mic-toggle.desktop` | `~/.local/share/applications/` |
| `config.example` | `~/.config/phone-mic/config` (only if missing) |
| `install.sh` / `uninstall.sh` | re-install after editing files here / remove everything |
| `LOG.md` | what was done, when, and test results |

Edit files **here**, then run `./install.sh` again. To undo everything: `./uninstall.sh`.
