// Host test driver for ZeeProxy (Linux x86_64 only).
// Compiles zee_proxy.cpp with -DZEE_PROXY_HOST_TEST and calls the hook
// functions directly (no Dobby on the host) against the Python stub
// servers in host_test_servers.py.
//
// Build + run (from tools/zee-proxy/):
//   g++ -std=c++17 -DZEE_PROXY_HOST_TEST -I. zee_proxy.cpp host_test_main.cpp -ldl -o /tmp/zeeproxy_test
//   python3 host_test_servers.py &   (from the same dir; writes *.log)
//   /tmp/zeeproxy_test; echo exit=$?

#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <stdio.h>
#include <string.h>
#include <string>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include "zee_proxy.h"

extern "C" int zee_connect(int, const struct sockaddr *, socklen_t);
extern "C" int zee_getaddrinfo(const char *, const char *, const struct addrinfo *,
                               struct addrinfo **);
extern "C" void zee_freeaddrinfo(struct addrinfo *);
extern "C" struct hostent *zee_gethostbyname(const char *);
extern "C" ssize_t zee_sendto(int, const void *, size_t, int, const struct sockaddr *,
                              socklen_t);

static int failures = 0;
static void check(bool cond, const char *name) {
    printf("%s %s\n", cond ? "PASS" : "FAIL", name);
    if (!cond) failures++;
}

static std::string read_all(int fd) {
    std::string out;
    char buf[4096];
    ssize_t n;
    while ((n = ::recv(fd, buf, sizeof(buf), 0)) > 0) out.append(buf, n);
    return out;
}

// Connect via the hook to a hostname:port and GET /; returns the body.
static std::string fetch_via_hook(const char *host, int port) {
    struct addrinfo *res = NULL;
    if (zee_getaddrinfo(host, "18081", NULL, &res) != 0 || !res) return "";
    int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        zee_freeaddrinfo(res);
        return "";
    }
    std::string out;
    if (zee_connect(fd, res->ai_addr, res->ai_addrlen) == 0) {
        std::string req = "GET / HTTP/1.0\r\nHost: ";
        req += host;
        req += "\r\n\r\n";
        ::send(fd, req.data(), req.size(), 0);
        out = read_all(fd);
    }
    ::close(fd);
    zee_freeaddrinfo(res);
    return out;
}

int main() {
    // 1. SOCKS5, no auth. Target is the stub HTTP server, reached by
    //    hostname so the hostname must travel inside the SOCKS request.
    check(ZeeProxy_TestInit("socks5", "127.0.0.1", 11080, "", ""),
          "socks5 no-auth init");
    std::string body = fetch_via_hook("target.test", 18081);
    check(body.find("ZEE-PROXY-TARGET-OK") != std::string::npos,
          "socks5 no-auth fetch via hostname");

    // 2. SOCKS5, user/pass.
    check(ZeeProxy_TestInit("socks5", "127.0.0.1", 11080, "u", "p"),
          "socks5 user/pass init");
    body = fetch_via_hook("target.test", 18081);
    check(body.find("ZEE-PROXY-TARGET-OK") != std::string::npos,
          "socks5 user/pass fetch via hostname");

    // 3. HTTP CONNECT proxy.
    check(ZeeProxy_TestInit("http", "127.0.0.1", 18080, "", ""),
          "http proxy init");
    body = fetch_via_hook("target.test", 18081);
    check(body.find("ZEE-PROXY-TARGET-OK") != std::string::npos,
          "http CONNECT fetch via hostname");

    // 4. Numeric-IP target still goes through the (SOCKS5) proxy.
    check(ZeeProxy_TestInit("socks5", "127.0.0.1", 11080, "", ""),
          "socks5 re-init for numeric test");
    struct sockaddr_in num;
    memset(&num, 0, sizeof(num));
    num.sin_family = AF_INET;
    num.sin_port = htons(18081);
    inet_pton(AF_INET, "127.0.0.1", &num.sin_addr);
    int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    bool numeric_ok = false;
    if (fd >= 0) {
        if (zee_connect(fd, (struct sockaddr *)&num, sizeof(num)) == 0) {
            const char *req = "GET / HTTP/1.0\r\nHost: x\r\n\r\n";
            ::send(fd, req, strlen(req), 0);
            numeric_ok = read_all(fd).find("ZEE-PROXY-TARGET-OK") != std::string::npos;
        }
        ::close(fd);
    }
    check(numeric_ok, "socks5 numeric-IP fetch");

    // 5. Same hostname twice -> same synthetic IP (stable mapping).
    struct addrinfo *a1 = NULL, *a2 = NULL;
    zee_getaddrinfo("target.test", "http", NULL, &a1);
    zee_getaddrinfo("target.test", "http", NULL, &a2);
    bool stable = a1 && a2 &&
                  ((struct sockaddr_in *)a1->ai_addr)->sin_addr.s_addr ==
                      ((struct sockaddr_in *)a2->ai_addr)->sin_addr.s_addr;
    if (a1) zee_freeaddrinfo(a1);
    if (a2) zee_freeaddrinfo(a2);
    check(stable, "synthetic DNS mapping is stable");

    // 6. gethostbyname returns a synthetic TEST-NET-2 address.
    struct hostent *he = zee_gethostbyname("target.test");
    bool synth = he && he->h_addrtype == AF_INET && he->h_length == 4 &&
                 (ntohl(*(uint32_t *)he->h_addr) & 0xffffff00u) == 0xc6336400u;
    check(synth, "gethostbyname returns synthetic 198.51.100.x");

    // 7. UDP is blocked (fail-closed).
    int ufd = ::socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    dst.sin_port = htons(53);
    inet_pton(AF_INET, "8.8.8.8", &dst.sin_addr);
    ssize_t sr = zee_sendto(ufd, "x", 1, 0, (struct sockaddr *)&dst, sizeof(dst));
    check(sr == -1 && errno == EACCES, "UDP sendto blocked with EACCES");
    ::close(ufd);

    // 8. IPv6 connect is blocked (fail-closed).
    int v6 = ::socket(AF_INET6, SOCK_STREAM, 0);
    struct sockaddr_in6 dst6;
    memset(&dst6, 0, sizeof(dst6));
    dst6.sin6_family = AF_INET6;
    dst6.sin6_port = htons(443);
    inet_pton(AF_INET6, "::1", &dst6.sin6_addr);
    int cr = zee_connect(v6, (struct sockaddr *)&dst6, sizeof(dst6));
    check(cr == -1 && errno == EADDRNOTAVAIL, "IPv6 connect blocked");
    ::close(v6);

    printf(failures == 0 ? "ALL HOST TESTS PASSED\n" : "%d HOST TEST(S) FAILED\n", failures);
    return failures == 0 ? 0 : 1;
}
