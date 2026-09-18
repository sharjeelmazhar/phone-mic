#!/usr/bin/env -S gjs -m
// phone-mic-gsconnect.js — add or remove the "Phone Mic ON / OFF / TOGGLE" entries in GSConnect's
// "Run Command" list for every paired phone, so they can be triggered from the KDE Connect app.
//   gjs -m phone-mic-gsconnect.js add|remove /full/path/to/phone-mic
// Exit codes: 0 done · 2 GSConnect not installed · 3 no paired device · 4 bad usage
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';

const [mode, bin] = ARGV;
if (!['add', 'remove'].includes(mode) || !bin) {
    printerr('usage: phone-mic-gsconnect.js add|remove /path/to/phone-mic');
    imports.system.exit(4);
}

const OUR = {
    'phone-mic-on':     {name: 'Phone Mic ON',     command: `${bin} on`},
    'phone-mic-off':    {name: 'Phone Mic OFF',    command: `${bin} off`},
    'phone-mic-toggle': {name: 'Phone Mic TOGGLE', command: `${bin} toggle`},
};
const UUID = 'gsconnect@andyholmes.github.io';
const BASE = '/org/gnome/shell/extensions/gsconnect/device/';

// GSConnect's schemas live inside the extension (user install or distro package)
const candidates = GLib.getenv('PHONE_MIC_GSCONNECT_SCHEMAS')   // env var only for testing
    ? [GLib.getenv('PHONE_MIC_GSCONNECT_SCHEMAS')]
    : [`${GLib.get_home_dir()}/.local/share/gnome-shell/extensions/${UUID}/schemas`,
       `/usr/share/gnome-shell/extensions/${UUID}/schemas`];
const schemaDir = candidates.find(d => GLib.file_test(`${d}/gschemas.compiled`, GLib.FileTest.EXISTS));
if (!schemaDir) {
    print('GSConnect is not installed (no schemas found).');
    imports.system.exit(2);
}
const source = Gio.SettingsSchemaSource.new_from_directory(schemaDir, Gio.SettingsSchemaSource.get_default(), false);
const deviceSchema = source.lookup('org.gnome.Shell.Extensions.GSConnect.Device', true);
const runcmdSchema = source.lookup('org.gnome.Shell.Extensions.GSConnect.Plugin.RunCommand', true);
if (!deviceSchema || !runcmdSchema) {
    print('GSConnect schemas are incomplete; is this a supported GSConnect version?');
    imports.system.exit(2);
}

// device ids = sub-directories of the dconf device path
let ids = [];
try {
    const [ok, out] = GLib.spawn_command_line_sync(`dconf list ${BASE}`);
    if (ok)
        ids = new TextDecoder().decode(out).split('\n').filter(l => l.endsWith('/')).map(l => l.slice(0, -1));
} catch (e) {
    printerr(`dconf list failed: ${e.message}`);
}

let touched = 0;
for (const id of ids) {
    const dev = new Gio.Settings({settings_schema: deviceSchema, path: `${BASE}${id}/`});
    const name = dev.get_string('name') || id;
    if (!dev.get_boolean('paired')) {
        print(`skip "${name}": not paired`);
        continue;
    }
    const rc = new Gio.Settings({settings_schema: runcmdSchema, path: `${BASE}${id}/plugin/runcommand/`});
    const cmds = rc.get_value('command-list').recursiveUnpack();   // {id: {name, command}} incl. GSConnect defaults
    for (const key of Object.keys(OUR)) {
        if (mode === 'add')
            cmds[key] = OUR[key];
        else
            delete cmds[key];
    }
    const packed = {};
    for (const [key, cmd] of Object.entries(cmds))
        packed[key] = new GLib.Variant('a{ss}', {name: String(cmd.name), command: String(cmd.command)});
    rc.set_value('command-list', new GLib.Variant('a{sv}', packed));
    print(`${mode === 'add' ? 'added to' : 'removed from'} "${name}": Phone Mic ON / OFF / TOGGLE`);
    touched++;
}
Gio.Settings.sync();
if (touched === 0) {
    print('GSConnect is installed but no paired phone was found. Pair the phone in GSConnect, then run: phone-mic kdeconnect');
    imports.system.exit(3);
}
