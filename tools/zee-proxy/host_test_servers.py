#!/usr/bin/env python3
"""Stub servers for the ZeeProxy host test (runs on Linux only).

- SOCKS5 stub on 127.0.0.1:11080: no-auth + user/pass (user "u", pass "p"),
  logs every CONNECT target to socks5.log, then relays to the target.
- HTTP CONNECT stub on 127.0.0.1:18080: accepts CONNECT, logs the target to
  http.log, then relays.
- Target HTTP server on 127.0.0.1:18081: always answers "ZEE-PROXY-TARGET-OK".

Run: python3 host_test_servers.py   (Ctrl-C / kill to stop)
"""
import socket
import threading

SOCKS_PORT = 11080
HTTP_PORT = 18080
TARGET_PORT = 18081
MARKER = b"ZEE-PROXY-TARGET-OK"


def relay(a, b):
    try:
        while True:
            data = a.recv(65536)
            if not data:
                break
            b.sendall(data)
    except OSError:
        pass
    finally:
        try:
            b.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def read_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("eof")
        buf += chunk
    return buf


def handle_socks(conn, addr):
    try:
        ver, nmethods = read_exact(conn, 2)
        methods = read_exact(conn, nmethods)
        chosen = 0x02 if 0x02 in methods else 0x00
        conn.sendall(bytes([0x05, chosen]))
        if chosen == 0x02:
            ver, ulen = read_exact(conn, 2)
            user = read_exact(conn, ulen)
            plen = read_exact(conn, 1)[0]
            pwd = read_exact(conn, plen)
            ok = user == b"u" and pwd == b"p"
            conn.sendall(bytes([0x01, 0x00 if ok else 0x01]))
            if not ok:
                return
        ver, cmd, rsv, atyp = read_exact(conn, 4)
        if atyp == 0x01:
            raw = read_exact(conn, 4)
            host = socket.inet_ntoa(raw)
        elif atyp == 0x03:
            ln = read_exact(conn, 1)[0]
            host = read_exact(conn, ln).decode()
        elif atyp == 0x04:
            raw = read_exact(conn, 16)
            host = socket.inet_ntop(socket.AF_INET6, raw)
        else:
            return
        port = int.from_bytes(read_exact(conn, 2), "big")
        with open("socks5.log", "a") as f:
            f.write(f"CONNECT {host}:{port}\n")
        # Test harness: the fake hostname maps to the local target server.
        dial_host = "127.0.0.1" if host == "target.test" else host
        target = socket.create_connection((dial_host, port), timeout=10)
        conn.sendall(bytes([0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]))
        t1 = threading.Thread(target=relay, args=(conn, target), daemon=True)
        t2 = threading.Thread(target=relay, args=(target, conn), daemon=True)
        t1.start()
        t2.start()
        t1.join()
        t2.join()
    except (OSError, ConnectionError):
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass


def handle_http_proxy(conn, addr):
    try:
        data = b""
        while b"\r\n\r\n" not in data and len(data) < 8192:
            chunk = conn.recv(4096)
            if not chunk:
                return
            data += chunk
        line = data.split(b"\r\n", 1)[0].decode(errors="replace")
        parts = line.split(" ")
        if len(parts) < 2 or parts[0] != "CONNECT":
            conn.sendall(b"HTTP/1.1 405 Nope\r\n\r\n")
            return
        hostport = parts[1]
        with open("http.log", "a") as f:
            f.write(f"CONNECT {hostport}\n")
        host, port = hostport.rsplit(":", 1)
        # Test harness: the fake hostname maps to the local target server.
        dial_host = "127.0.0.1" if host == "target.test" else host
        target = socket.create_connection((dial_host, int(port)), timeout=10)
        conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        t1 = threading.Thread(target=relay, args=(conn, target), daemon=True)
        t2 = threading.Thread(target=relay, args=(target, conn), daemon=True)
        t1.start()
        t2.start()
        t1.join()
        t2.join()
    except (OSError, ValueError):
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass


def handle_target(conn, addr):
    try:
        data = b""
        while b"\r\n\r\n" not in data and len(data) < 8192:
            chunk = conn.recv(4096)
            if not chunk:
                break
            data += chunk
        body = MARKER
        conn.sendall(
            b"HTTP/1.0 200 OK\r\nContent-Length: %d\r\n\r\n%s" % (len(body), body)
        )
    except OSError:
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass


def serve(port, handler):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(16)
    print(f"listening on 127.0.0.1:{port}", flush=True)
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=handler, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    for path in ("socks5.log", "http.log"):
        open(path, "w").close()
    threading.Thread(target=serve, args=(SOCKS_PORT, handle_socks), daemon=True).start()
    threading.Thread(target=serve, args=(HTTP_PORT, handle_http_proxy), daemon=True).start()
    threading.Thread(target=serve, args=(TARGET_PORT, handle_target), daemon=True).start()
    print("stub servers up", flush=True)
    threading.Event().wait()
