// ZeeProxy — per-clone proxy for Zee Clone (v3.1.0).
//
// One clone, one country: the host app writes one JSON config per clone at
//   <host filesDir>/proxy/<package>_<userId>.json
// and the engine calls ZeeProxy_Enable() for that clone from
// BActivityThread.handleBindApplication (before the cloned app's code runs).
// This file installs Dobby inline hooks on libc's connect/getaddrinfo/
// gethostbyname/sendto/sendmsg/send INSIDE THE CLONE PROCESS ONLY, and
// routes the clone's TCP through the configured SOCKS5 or HTTP proxy.
//
// Design (fail-closed):
//  - No config file, or enabled=false -> NO hooks installed, traffic untouched.
//  - DNS never leaves the device: getaddrinfo returns synthetic TEST-NET-2
//    addresses (198.51.100.x) and the real hostname is carried to the proxy
//    inside the SOCKS5 CONNECT (remote DNS). The proxy host itself is the
//    only name ever resolved for real, once, before the hooks go in.
//  - UDP (QUIC, DNS-over-UDP) and IPv6 are BLOCKED for proxied clones:
//    there is no UDP/IPv6 inside SOCKS5 here, so they fail instead of
//    leaking around the proxy. Apps fall back to TCP/IPv4.
//  - The proxy connection itself goes through the ORIGINAL connect()
//    (dest == proxy IP is passed through), so there is no recursion.
//  - If the proxy host cannot be resolved, the hooks still go in and every
//    connect() fails closed (ECONNREFUSED) — a bad proxy bricks the clone's
//    network instead of silently leaking it. The reason is written to the
//    per-clone .status file next to the config.
//
// This code is Zee Clone's own. It uses the Dobby hooking library that
// NewBlackbox already vendors and links (Bcore/src/main/cpp/Dobby).
// Host-test mode (ZEE_PROXY_HOST_TEST): builds on Linux x86_64 with the
// JNI/Dobby parts stubbed out, so the SOCKS5/HTTP handshake and the
// synthetic-DNS logic can be tested against local stub servers. See
// tools/zee-proxy/host_test_main.cpp.

#pragma once

// Host-test builds (Linux x86_64, -DZEE_PROXY_HOST_TEST) have no JNI.
#ifndef ZEE_PROXY_HOST_TEST
#include <jni.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

#ifndef ZEE_PROXY_HOST_TEST
// JNI entry, registered on NativeCore as
//   enableProxy(String packageName, int userId, String hostFilesDir)
// Called once per clone process, from BActivityThread.handleBindApplication.
// Never throws; all failures are logged + written to the .status file.
void ZeeProxy_Enable(JNIEnv *env, jclass clazz, jstring packageName, jint userId,
                     jstring hostFilesDir);
#else
// Host-test entry (Linux only): configure + activate directly, no files/JNI.
bool ZeeProxy_TestInit(const char *type, const char *host, int port,
                       const char *user, const char *pass);
#endif

#ifdef __cplusplus
}
#endif
