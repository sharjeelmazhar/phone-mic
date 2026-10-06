#!/usr/bin/env python3
"""End-to-end tests of bin/phone-mic-daemon against a fake phone (tests/fake_phone.py), in the real PipeWire.
Runs next to an installed Phone Mic without touching it: own ports, own loopback nodes, own config folder.
    python3 tests/test_daemon.py
"""
import array
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fake_phone import FakePhone, d  # noqa: E402

DAEMON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bin", "phone-mic-daemon")
TCP, UDP = 47730, 47731
failures = 0


def check(ok, what):
    global failures
    print(("  ok   " if ok else "  FAIL ") + what, flush=True)
    failures += 0 if ok else 1


def wait(cond, secs, step=0.2):
    end = time.time() + secs
    while time.time() < end:
        if cond():
            return True
        time.sleep(step)
    return False


class Env:
    def __init__(self):
        self.tmp = tempfile.mkdtemp(prefix="phone-mic-test-")
        self.conf = os.path.join(self.tmp, "config")
        self.run = os.path.join(self.tmp, "run")
        os.makedirs(os.path.join(self.conf, "phone-mic"))
        self.daemon = None
        self.out = []
        self.loop = subprocess.Popen(["pw-loopback", "--name", "pmtest", "--channels", "1", "--channel-map", "[ MONO ]",
            "--capture-props", "node.name=pmtest_in node.description=pmtest-feed node.autoconnect=false audio.position=[MONO]",
            "--playback-props", "media.class=Audio/Source node.name=pmtest_src node.description=pmtest priority.session=1 priority.driver=1 audio.position=[MONO]"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def config(self, text):
        open(os.path.join(self.conf, "phone-mic", "config"), "w").write(text)

    def start(self):
        env = dict(os.environ, XDG_CONFIG_HOME=self.conf, PHONE_MIC_RUN_DIR=self.run, PHONE_MIC_TCP_PORT=str(TCP),
                   PHONE_MIC_UDP_PORT=str(UDP), PHONE_MIC_TARGET="pmtest_in", PHONE_MIC_STREAM="pmtest_stream",
                   PHONE_MIC_SOURCE="pmtest_src")
        self.daemon = subprocess.Popen([sys.executable, DAEMON], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        threading.Thread(target=lambda: [self.out.append(l.rstrip()) for l in self.daemon.stdout], daemon=True).start()

    def stop(self):
        if self.daemon and self.daemon.poll() is None:
            self.daemon.send_signal(signal.SIGTERM)
            try:
                return self.daemon.wait(5)
            except subprocess.TimeoutExpired:
                self.daemon.kill()
        return None

    def state(self):
        try:
            return open(os.path.join(self.run, "state")).read().split()
        except OSError:
            return []

    def phones(self):
        try:
            import json
            return json.load(open(os.path.join(self.conf, "phone-mic", "phones.json")))["phones"]
        except (OSError, ValueError):
            return {}

    def record(self, secs=1.5):
        """Samples recorded from the test "Phone Mic" device."""
        p = subprocess.Popen(["pw-record", "--target", "pmtest_src", "--rate", "48000", "--channels", "1", "--format", "s16",
                              "--raw", "-"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        time.sleep(secs); p.terminate()
        data = p.stdout.read()
        return array.array("h", data[:len(data) // 2 * 2])

    def close(self):
        self.stop()
        self.loop.terminate()
        shutil.rmtree(self.tmp, ignore_errors=True)


def tone_ok(samples):
    """A clean 440 Hz tone: loud enough and about 880 zero crossings per second."""
    s = samples[len(samples) // 4:]                          # skip the start-up
    if len(s) < 24000:
        return False, f"only {len(s)} samples"
    peak = max(abs(x) for x in s)
    crossings = sum(1 for a, b in zip(s, s[1:]) if (a < 0) != (b < 0)) * 48000 / len(s)
    return peak > 8000 and 800 < crossings < 960, f"peak {peak}, {crossings:.0f} crossings/s"


def unit_tests():
    print("protocol helpers")
    k = d.pair_key(bytes([1] * 32), "aa", "bb")
    check(k.hex() == "48792092ebb4a7032c5c4b6956cbf1ec8d4c06d726c0e180cc72229f5b6c1466", "pair key matches the app (ProtoTest)")
    check(d.pair_code(k) == "200505", "pairing code matches the app")
    check(d.Cipher(d.session_key(k, "n1", "n2")).apply(bytes(40)).hex() ==
          "d21734fb4e403ba2251338b301e7beab320e9d742b128a958685673527a24c5ecd08a56089a47b3b", "keystream matches the app")
    c1, c2 = d.Cipher(k), d.Cipher(k)
    data = os.urandom(1000)
    check(c1.apply(data) == c2.apply(data[:7]) + c2.apply(data[7:500]) + c2.apply(data[500:]), "cipher independent of chunking")
    a, b = d.Dh(), d.Dh()
    check(a.shared(b.public) == b.shared(a.public), "Diffie-Hellman agrees")
    try:
        a.shared("1"); check(False, "trivial DH key rejected")
    except ValueError:
        check(True, "trivial DH key rejected")
    for n in ["Redmi Note 11", "smr's desktop", "Ünïcødé 名前", "a%b"]:
        check(d.decode_name(d.encode_name(n)) == n, f"name round trip: {n}")
    check(d.encode_name("Redmi Note 11") == "Redmi%20Note%2011", "names encoded like the app")

    print("subnet scan")
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.77", TCP)); srv.listen()
    real, d.local_networks = d.local_networks, lambda: [("127.0.0.1", 24)]
    old_port, d.TCP_PORT = d.TCP_PORT, TCP
    t = time.time(); found = d.scan_hosts(); took = time.time() - t
    d.local_networks, d.TCP_PORT = real, old_port
    srv.close()
    check(found == ["127.0.0.77"], f"scan finds the listening host only ({found})")
    check(took < 2, f"scan of 254 addresses takes {took:.1f} s")
    nets = d.local_networks()
    check(all(not ip.startswith("127.") for ip, _ in nets), f"local networks without loopback: {nets}")

    print("config")
    tmp = tempfile.mkdtemp()
    os.makedirs(os.path.join(tmp, "phone-mic"))
    open(os.path.join(tmp, "phone-mic", "config"), "w").write('# c\nAUDIO_SOURCE="mic-voice-communication"\nBUFFER_MS=200 # x\nOTHER=1\n')
    old = d.CONF_DIR; d.CONF_DIR = os.path.join(tmp, "phone-mic"); d.load_config(); d.CONF_DIR = old
    check(d.CONFIG["AUDIO_SOURCE"] == "voice-communication", "version 1 source name accepted")
    check(d.CONFIG["BUFFER_MS"] == "200", "values with comments")
    shutil.rmtree(tmp)


def end_to_end():
    e = Env()
    try:
        e.config("SCAN=0\n")
        time.sleep(1)
        print("first phone: pairing without any command")
        phone = FakePhone(TCP, UDP, name="Redmi Note 11").start()
        e.start()
        check(wait(lambda: e.state()[:1] == ["pairing"], 15), f"daemon asks to pair (state {e.state()})")
        codes = [x[1] for x in phone.events if x[0] == "pair"]
        check(bool(codes) and e.state()[1:2] == codes[:1], f"computer shows the same code as the phone ({e.state()} / {codes})")
        hello = next((x for x in phone.events if x[0] == "hello"), None)
        check(hello is not None and hello[2] == socket.gethostname() and hello[3] == "mic", f"computer introduces itself ({hello})")
        check(wait(lambda: len(e.phones()) == 1, 10), "phone saved after Allow")
        st = os.stat(os.path.join(e.conf, "phone-mic", "phones.json")).st_mode & 0o777
        check(st == 0o600, f"phones.json (holds keys) is private: {oct(st)}")

        print("streaming")
        check(wait(lambda: e.state()[:1] == ["streaming"], 15), f"streaming after pairing ({e.state()})")
        check(e.state()[1:2] == ["Redmi%20Note%2011"], "state names the phone")
        time.sleep(1.5)
        ok, info = tone_ok(e.record(2))
        check(ok, f"the phone's 440 Hz tone arrives in the Phone Mic device: {info}")
        check(sum(1 for x in phone.events if x[0] == "keepalive") >= 2, "keepalives reach the phone")
        links = subprocess.run(["pw-link", "-l"], capture_output=True, text=True).stdout
        check("pmtest_stream:output_MONO" in links and "pmtest_in:input_MONO" in links, "stream linked to the loopback only")

        print("phone leaves (other Wi-Fi / out of range) and comes back")
        phone.vanish()
        t = time.time()
        check(wait(lambda: e.state()[:1] == ["waiting"], 10), f"noticed within {time.time() - t:.1f} s")
        time.sleep(4)
        phone2 = FakePhone(TCP, UDP, name="Redmi Note 11", pid=phone.pid); phone2.computers = phone.computers
        phone2.start(); t = time.time()
        check(wait(lambda: e.state()[:1] == ["streaming"], 15), f"reconnected by itself after {time.time() - t:.1f} s")
        check(not any(x[0] == "pair" for x in phone2.events), "no new pairing needed")
        ok, info = tone_ok(e.record(1.5))
        check(ok, f"audio again: {info}")

        print("connection silently dead (phone switched network without closing it)")
        phone2.frozen = True; t = time.time()
        check(wait(lambda: e.state()[:1] == ["waiting"], 10), f"noticed within {time.time() - t:.1f} s")
        phone2.frozen = False; t = time.time()
        check(wait(lambda: e.state()[:1] == ["streaming"], 15), f"streaming again after {time.time() - t:.1f} s")

        print("phone busy with another computer")
        phone2.vanish(); time.sleep(1)
        bp = FakePhone(TCP, UDP, name="Redmi Note 11", pid=phone.pid); bp.computers = dict(phone.computers); bp.busy = True
        bp.start()
        check(wait(lambda: any(x[0] == "busy" for x in bp.events), 25), "computer asks the busy phone")
        time.sleep(3)
        check(e.state()[:1] == ["waiting"], "BUSY: no stream, keeps waiting")
        bp.busy = False
        check(wait(lambda: e.state()[:1] == ["streaming"], 15), "streams once the phone is free")
        bp.vanish(); time.sleep(1)
        phone2 = FakePhone(TCP, UDP, name="Redmi Note 11", pid=phone.pid); phone2.computers = phone.computers; phone2.start()
        check(wait(lambda: e.state()[:1] == ["streaming"], 15), "back with the usual phone")


        print("a second phone is not paired without `phone-mic pair`")
        phone2.vanish(); time.sleep(1)
        other = FakePhone(TCP, UDP, name="Other Phone").start()
        time.sleep(8)
        check(not any(x[0] == "pair" for x in other.events), "unknown phone ignored")
        os.makedirs(e.run, exist_ok=True); open(os.path.join(e.run, "pair"), "w").close()
        other.allow = False
        check(wait(lambda: any(x[0] == "pair" for x in other.events), 15), "after `phone-mic pair` it is asked")
        time.sleep(12)
        check(sum(1 for x in other.events if x[0] == "pair") == 1, "after Deny it is not asked again and again")
        check(len(e.phones()) == 1, "denied phone not saved")
        other.vanish(); time.sleep(1)
        os.remove(os.path.join(e.run, "pair"))                  # the 5-minute window of `phone-mic pair` is over

        print("security")
        imp = FakePhone(TCP, UDP, name="Redmi Note 11", pid=phone.pid)
        imp.computers = {cid: os.urandom(32) for cid in phone.computers}       # claims to be the phone, wrong key
        imp.start()
        time.sleep(7)
        check(e.state()[:1] != ["streaming"], "phone with the wrong key gets no stream")
        check(len(e.phones()) == 1, "and the real phone's pairing is kept")
        imp.vanish(); time.sleep(1)
        imp2 = FakePhone(TCP, UDP, name="Redmi Note 11", pid=phone.pid)
        imp2.computers = {cid: os.urandom(32) for cid in phone.computers}; imp2.accept_any = True; imp2.bad_proof = True
        imp2.start()
        time.sleep(7)
        check(e.state()[:1] != ["streaming"], "phone that cannot prove the key is not played")
        imp2.vanish(); time.sleep(1)
        imp3 = FakePhone(TCP, UDP, name="Redmi Note 11", pid=phone.pid)        # knows no computer: asks to pair
        imp3.start()
        time.sleep(7)
        check(not any(x[0] == "pair" for x in imp3.events) and len(e.phones()) == 1,
              "a paired phone's id cannot re-pair without `phone-mic pair`")
        imp3.vanish(); time.sleep(1)

        print("QR pairing")
        cid = next(iter(phone.computers))
        secret = os.urandom(16)
        os.makedirs(e.run, exist_ok=True)
        open(os.path.join(e.run, "pair-secret"), "w").write(secret.hex()); open(os.path.join(e.run, "pair"), "w").close()
        qp = FakePhone(TCP, UDP, name="QR Phone", allow=False); qp.start()          # not scanned yet
        check(wait(lambda: any(x[0] == "noqr" for x in qp.events), 15), "before the scan: asked, quietly refused (no dialog)")
        check(not any(x[0] == "pair" for x in qp.events) and e.state()[:1] != ["pairing"], "no code shown anywhere")
        qp.qr = {cid: secret}; t0 = time.time()                                      # now the QR code is scanned
        check(wait(lambda: len(e.phones()) == 2, 15), f"phone that scanned the QR code is paired without a tap ({time.time() - t0:.1f} s)")
        check(any(x[0] == "qrpaired" for x in qp.events), "and it was the QR proof that did it")
        check(not os.path.exists(os.path.join(e.run, "pair-secret")), "QR secret used up")
        check(wait(lambda: e.state()[:1] == ["streaming"], 15), "streams right after")
        qp.vanish(); time.sleep(1)
        open(os.path.join(e.run, "pair-secret"), "w").write(os.urandom(16).hex()); open(os.path.join(e.run, "pair"), "w").close()
        wrong = FakePhone(TCP, UDP, name="Wrong QR", allow=False); wrong.qr = {cid: secret}; wrong.start()   # old secret
        time.sleep(8)
        check(len(e.phones()) == 2 and not any(x[0] == "qrpaired" for x in wrong.events), "an old / wrong QR secret does not pair")
        wrong.vanish(); time.sleep(1)
        os.remove(os.path.join(e.run, "pair")); os.remove(os.path.join(e.run, "pair-secret"))

        print("stopping")
        t = time.time(); rc = e.stop()
        check(rc == 0, f"SIGTERM: clean exit ({rc}) in {time.time() - t:.1f} s")
        time.sleep(0.5)
        check("pmtest_stream" not in subprocess.run(["pw-link", "-o"], capture_output=True, text=True).stdout, "no stream left behind")
    finally:
        if failures:
            print("--- daemon output:\n" + "\n".join(e.out[-40:]))
        e.close()


if __name__ == "__main__":
    unit_tests()
    end_to_end()
    print("FAILED: %d" % failures if failures else "all passed")
    sys.exit(1 if failures else 0)
