# ZeeProxy — per-clone proxy (Zee Clone v3.1.0)

One clone, one country: a normal VPN is device-wide (one country for the
whole phone), so per-clone routing needs engine-level proxying. This module
installs Dobby inline hooks on libc's `connect` / `getaddrinfo` /
`gethostbyname` / `sendto` / `sendmsg` / `send` **inside the clone process
only**, and routes that clone's TCP through its configured SOCKS5 or HTTP
proxy. Design notes are in `zee_proxy.h`.

## Files

- `zee_proxy.h` / `zee_proxy.cpp` — the module. Shipped into the Bcore
  native build by `tools/patch-bcore-split.py` (copies the files, adds the
  .cpp to `Android.mk`, registers the `enableProxy` JNI entry in
  `BoxCore.cpp`, declares it in `NativeCore.java`). The call site is
  `BActivityThread.handleBindApplication` (patched copy in
  `tools/bcore-split-patch/`), right after `IOCore.enableRedirect`.
- `host_test_main.cpp` + `host_test_servers.py` — Linux host test of the
  handshake + synthetic-DNS + fail-closed logic (no Android needed).

## Config

The host app writes one JSON file per clone (see
`app/src/main/java/com/zee/clone/data/ProxySettings.kt`):
`<filesDir>/proxy/<package>_<userId>.json`
`{"enabled":true,"type":"socks5","host":"1.2.3.4","port":1080,"user":"","pass":"","country":"DE"}`

No config file (or `enabled:false`) = no hooks, traffic untouched.
A `.status` file next to it records what the hook did at clone start.

## Host test

```sh
cd tools/zee-proxy
g++ -std=c++17 -Wall -DZEE_PROXY_HOST_TEST -I. zee_proxy.cpp host_test_main.cpp -ldl -o /tmp/zeeproxy_test
python3 host_test_servers.py &   # SOCKS5 :11080, HTTP proxy :18080, target :18081
/tmp/zeeproxy_test               # expect: ALL HOST TESTS PASSED
```

12 checks: SOCKS5 no-auth + user/pass fetch via hostname, HTTP CONNECT
fetch, numeric-IP fetch, stable synthetic-DNS mapping, gethostbyname
synthetic address, UDP blocked, IPv6 blocked. The stub logs prove the
hostname travels inside the SOCKS request (remote DNS, no local leak).

What the host test does NOT prove: the Dobby installation inside a real
clone process, and the BActivityThread call site. Those are compile-verified
by CI and must be phone-tested (see `~/workspace/zeeclone-v310/BUILD-NOTE.md`
for the test plan).
