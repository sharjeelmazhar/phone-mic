// Phone Mic — Quick Settings tile for the phone-mic scripts.
// Shows Off / Waiting for phone / Streaming, click toggles `phone-mic on|off`.
// A small phone icon appears in the top bar while the phone mic is streaming.

import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import GObject from 'gi://GObject';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import {QuickToggle, SystemIndicator} from 'resource:///org/gnome/shell/ui/quickSettings.js';

const PHONE_MIC = GLib.build_filenamev([GLib.get_home_dir(), '.local', 'bin', 'phone-mic']);
const POLL_SECONDS = 3;

// Run `phone-mic <args>` without blocking the shell; resolves to trimmed stdout ('' on any error).
function phoneMic(...args) {
    return new Promise(resolve => {
        try {
            const proc = Gio.Subprocess.new([PHONE_MIC, ...args],
                Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_SILENCE);
            proc.communicate_utf8_async(null, null, (p, res) => {
                try {
                    const [, out] = p.communicate_utf8_finish(res);
                    resolve((out ?? '').trim());
                } catch {
                    resolve('');
                }
            });
        } catch {
            resolve('');
        }
    });
}

const SUBTITLES = {
    streaming: 'Streaming',
    waiting: 'Waiting for phone',
    off: 'Off',
    '': 'phone-mic not installed',
};

const PhoneMicToggle = GObject.registerClass(
class PhoneMicToggle extends QuickToggle {
    _init() {
        super._init({
            title: 'Phone Mic',
            iconName: 'audio-input-microphone-symbolic',
            toggleMode: false,
        });
        this._busy = false;
        this.connect('clicked', () => this._onClicked().catch(logError));
    }

    async _onClicked() {
        if (this._busy)
            return;
        this._busy = true;
        const turnOn = !this.checked;
        this.checked = turnOn;
        this.subtitle = turnOn ? 'Starting…' : 'Stopping…';
        await phoneMic(turnOn ? 'on' : 'off');
        this._busy = false;
        await this.refresh();
    }

    // Returns the state string: streaming | waiting | off | ''
    async refresh() {
        if (this._busy)
            return null;
        const state = await phoneMic('state');
        this.checked = state === 'streaming' || state === 'waiting';
        this.subtitle = SUBTITLES[state] ?? state;
        return state;
    }
});

const PhoneMicIndicator = GObject.registerClass(
class PhoneMicIndicator extends SystemIndicator {
    _init(extensionPath) {
        super._init();
        this._indicator = this._addIndicator();
        // own icon: a phone with a microphone inside (icons/phone-mic-symbolic.svg), recoloured by the shell
        this._indicator.gicon = Gio.icon_new_for_string(`${extensionPath}/icons/phone-mic-symbolic.svg`);
        this._indicator.visible = false;
        this._toggle = new PhoneMicToggle();
        this.quickSettingsItems.push(this._toggle);
    }

    async refresh() {
        const state = await this._toggle.refresh();
        if (state !== null)
            this._indicator.visible = state === 'streaming';
    }
});

export default class PhoneMicExtension extends Extension {
    enable() {
        this._indicator = new PhoneMicIndicator(this.path);
        const qs = Main.panel.statusArea.quickSettings;
        qs.addExternalIndicator(this._indicator);

        this._indicator.refresh().catch(logError);
        this._pollId = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, POLL_SECONDS, () => {
            this._indicator?.refresh().catch(logError);
            return GLib.SOURCE_CONTINUE;
        });
        // refresh immediately when the menu opens, so the tile is never stale
        this._menuId = qs.menu.connect('open-state-changed', (_menu, open) => {
            if (open)
                this._indicator?.refresh().catch(logError);
        });
    }

    disable() {
        if (this._pollId) {
            GLib.source_remove(this._pollId);
            this._pollId = null;
        }
        if (this._menuId) {
            Main.panel.statusArea.quickSettings.menu.disconnect(this._menuId);
            this._menuId = null;
        }
        if (this._indicator) {
            this._indicator.quickSettingsItems.forEach(item => item.destroy());
            this._indicator.destroy();
            this._indicator = null;
        }
    }
}
