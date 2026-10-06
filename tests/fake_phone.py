"""A stand-in for the Phone Mic app, speaking the same protocol (see Proto.kt), for tests/test_daemon.py.
Plays a 440 Hz tone as its "microphone"."""
import importlib.machinery
import math
import os
import secrets
import socket
import struct
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
d = importlib.machinery.SourceFileLoader("daemon", os.path.join(HERE, "..", "bin", "phone-mic-daemon")).load_module()


def tone(start_sample, n, freq=440, amp=12000):
    return struct.pack("<%dh" % n, *(int(amp * math.sin(2 * math.pi * freq * (start_sample + i) / d.RATE)) for i in range(n)))


class FakePhone:
    def __init__(self, port, udp_port, name="Fake Phone", pid=None, allow=True):
        self.port, self.udp_port, self.name = port, udp_port, name
        self.pid = pid or secrets.token_hex(8)
        self.allow = allow                  # answer to pairing requests
        self.computers = {}                 # computer id -> key
        self.events = []                    # ("pair", code) / ("stream", cid) / ("keepalive",) / ...
        self.bad_proof = False              # impostor: answers OK with a wrong proof
        self.frozen = False                 # connection stays open but nothing arrives (Wi-Fi switched, no FIN)
        self.accept_any = False             # impostor: does not check the computer's proof
        self.server = None
        self.conns = []
        self.running = False

    def start(self):
        self.server = socket.socket()
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind(("127.0.0.1", self.port))
        self.server.listen()
        self.running = True
        threading.Thread(target=self._accept, daemon=True).start()
        threading.Thread(target=self._announce, daemon=True).start()
        return self

    def vanish(self):
        """Like the phone switching to another network: everything just stops."""
        self.running = False
        try:
            self.server.shutdown(socket.SHUT_RDWR)      # wakes the accept() thread; close() alone would not
        except OSError:
            pass
        self.server.close()
        for c in self.conns:
            try:
                c.shutdown(socket.SHUT_RDWR); c.close()
            except OSError:
                pass
        self.conns = []

    def _announce(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        while self.running:
            s.sendto(f"PHONEMIC 1 {self.pid} {self.port} {d.encode_name(self.name)}".encode(), ("127.0.0.1", self.udp_port))
            time.sleep(1)

    def _accept(self):
        while self.running:
            try:
                c, _ = self.server.accept()
            except OSError:
                return
            self.conns.append(c)
            threading.Thread(target=self._handle, args=(c,), daemon=True).start()

    def _handle(self, c):
        f = c.makefile("rwb", buffering=0)
        def send(line): c.sendall((line + "\n").encode())
        def read(): return f.readline().decode().split()
        try:
            send(f"PHONEMIC 1 {self.pid} 9.9.9 {d.encode_name(self.name)}")
            _, cid, cname, nc, source = read()
            self.events.append(("hello", cid, d.decode_name(cname), source))
            key = self.computers.get(cid)
            if key is None:
                np_ = secrets.token_hex(16); dh = d.Dh()
                send(f"PAIR {np_} {dh.public}")
                _, pub = read()
                key = d.pair_key(dh.shared(pub), np_, nc)
                self.events.append(("pair", d.pair_code(key)))
                time.sleep(1.5)            # the person reading the code
                if self.allow:
                    self.computers[cid] = key; send("PAIRED")
                else:
                    send("DENIED")
                return
            np_ = secrets.token_hex(16)
            send(f"AUTH {np_}")
            _, pr = read()
            if pr != d.proof(key, "C", np_, nc) and not self.accept_any:
                send("DENIED"); self.events.append(("badproof",)); return
            send(f"OK 48000 1 {'00' * 32 if self.bad_proof else d.proof(key, 'P', np_, nc)}")
            self.events.append(("stream", cid))
            cipher = d.Cipher(d.session_key(key, np_, nc))
            c.settimeout(6)
            def keepalives():
                try:
                    while c.recv(1):
                        self.events.append(("keepalive",))
                except OSError:
                    pass
            threading.Thread(target=keepalives, daemon=True).start()
            n = 0; t0 = time.time()
            while self.running:
                if self.frozen:
                    time.sleep(0.1); t0 += 0.1; continue
                c.sendall(cipher.apply(tone(n, 960))); n += 960
                time.sleep(max(0, t0 + n / d.RATE - time.time()))
        except (OSError, ValueError):
            pass
        finally:
            try:
                c.close()
            except OSError:
                pass
