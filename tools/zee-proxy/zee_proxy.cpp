// ZeeProxy — per-clone proxy. See zee_proxy.h for the design.
// Own code of Zee Clone. Uses Dobby (vendored + linked by NewBlackbox)
// for the inline hooks. No other third-party code.

#include "zee_proxy.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <map>
#include <vector>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>

#ifdef ZEE_PROXY_HOST_TEST
#include <dlfcn.h>
// Linux test build: log to stderr, no JNI, no Dobby (the test calls the
// hook functions directly; the "orig" pointers are the real libc ones).
static void zp_log(int is_err, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "[ZeeProxy]%s ", is_err ? " E" : " D");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
}
#else
#include <android/log.h>
#include <dlfcn.h>
#include "Dobby/dobby.h"
static void zp_log(int is_err, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    __android_log_vprint(is_err ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG,
                         "ZeeProxy", fmt, ap);
    va_end(ap);
}
#endif
#define ZP_LOGD(...) zp_log(0, __VA_ARGS__)
#define ZP_LOGE(...) zp_log(1, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

struct ProxyConfig {
    bool enabled = false;
    std::string type = "socks5";   // "socks5" | "http"
    std::string host;
    int port = 0;
    std::string user;
    std::string pass;
    std::string country;
};

static ProxyConfig g_cfg;          // valid once g_have_cfg is true
static bool g_have_cfg = false;
static bool g_active = false;      // hooks installed + proxy usable
static bool g_installed = false;   // hooks installed (even if fail-closed)
static bool g_proxy_ip_ok = false; // proxy host resolved to g_proxy_ip
static uint32_t g_proxy_ip = 0;    // network byte order
static std::string g_status_path;  // per-clone .status file ("" = none)

// Synthetic DNS range: TEST-NET-2 (RFC 5737), never routed on a real network.
static const uint32_t kSyntheticBase = 0xC6336400u;  // 198.51.100.0
static const int kSyntheticMax = 250;

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static std::map<std::string, uint32_t> g_host_to_ip;  // hostname -> synthetic ip (host order)
static std::map<uint32_t, std::string> g_ip_to_host;  // synthetic ip (host order) -> hostname
static uint32_t g_next_synth = 1;
static std::vector<struct addrinfo *> g_synth_results;  // addrinfo chains we allocated

// --- minimal JSON readers (our own writer produces this exact shape) --------
static bool json_string(const std::string &js, const char *key, std::string &out) {
    std::string pat = std::string("\"") + key + "\"";
    size_t p = js.find(pat);
    if (p == std::string::npos) return false;
    p = js.find(':', p + pat.size());
    if (p == std::string::npos) return false;
    p = js.find('"', p + 1);
    if (p == std::string::npos) return false;
    std::string val;
    for (size_t i = p + 1; i < js.size(); i++) {
        char c = js[i];
        if (c == '\\' && i + 1 < js.size()) { val += js[++i]; continue; }
        if (c == '"') { out = val; return true; }
        val += c;
    }
    return false;
}
static bool json_int(const std::string &js, const char *key, int &out) {
    std::string pat = std::string("\"") + key + "\"";
    size_t p = js.find(pat);
    if (p == std::string::npos) return false;
    p = js.find(':', p + pat.size());
    if (p == std::string::npos) return false;
    out = atoi(js.c_str() + p + 1);
    return true;
}
static bool json_bool(const std::string &js, const char *key, bool &out) {
    std::string pat = std::string("\"") + key + "\"";
    size_t p = js.find(pat);
    if (p == std::string::npos) return false;
    p = js.find(':', p + pat.size());
    if (p == std::string::npos) return false;
    size_t s = p + 1;
    while (s < js.size() && (js[s] == ' ' || js[s] == '\t')) s++;
    if (js.compare(s, 4, "true") == 0) { out = true; return true; }
    if (js.compare(s, 5, "false") == 0) { out = false; return true; }
    return false;
}

static std::string read_file(const std::string &path) {
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) return std::string();
    std::string data;
    char buf[1024];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) data.append(buf, n);
    fclose(f);
    return data;
}

static void write_status(const std::string &text) {
    if (g_status_path.empty()) return;
    FILE *f = fopen(g_status_path.c_str(), "wb");
    if (!f) return;
    fwrite(text.c_str(), 1, text.size(), f);
    fclose(f);
}

// ---------------------------------------------------------------------------
// Original libc functions (captured before the hooks go in).
// Every internal call uses these — never the plain names, which would
// hit our own hooks once installed.
// ---------------------------------------------------------------------------

typedef int (*connect_fn)(int, const struct sockaddr *, socklen_t);
typedef int (*getaddrinfo_fn)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
typedef void (*freeaddrinfo_fn)(struct addrinfo *);
typedef struct hostent *(*gethostbyname_fn)(const char *);
typedef struct hostent *(*gethostbyname2_fn)(const char *, int);
typedef ssize_t (*sendto_fn)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
typedef ssize_t (*sendmsg_fn)(int, const struct msghdr *, int);
typedef ssize_t (*send_fn)(int, const void *, size_t, int);
typedef ssize_t (*recv_fn)(int, void *, size_t, int);

static connect_fn orig_connect = NULL;
static getaddrinfo_fn orig_getaddrinfo = NULL;
static freeaddrinfo_fn orig_freeaddrinfo = NULL;
static gethostbyname_fn orig_gethostbyname = NULL;
static gethostbyname2_fn orig_gethostbyname2 = NULL;
static sendto_fn orig_sendto = NULL;
static sendmsg_fn orig_sendmsg = NULL;
static send_fn orig_send = NULL;
static recv_fn orig_recv = NULL;

static void *resolve_orig(const char *name) {
#ifdef ZEE_PROXY_HOST_TEST
    void *p = dlsym(RTLD_NEXT, name);
#else
    void *p = DobbySymbolResolver("libc.so", name);
    if (!p) p = dlsym(RTLD_DEFAULT, name);
#endif
    return p;
}

static bool capture_origs() {
    orig_connect = (connect_fn)resolve_orig("connect");
    orig_getaddrinfo = (getaddrinfo_fn)resolve_orig("getaddrinfo");
    orig_freeaddrinfo = (freeaddrinfo_fn)resolve_orig("freeaddrinfo");
    orig_gethostbyname = (gethostbyname_fn)resolve_orig("gethostbyname");
    orig_gethostbyname2 = (gethostbyname2_fn)resolve_orig("gethostbyname2");
    orig_sendto = (sendto_fn)resolve_orig("sendto");
    orig_sendmsg = (sendmsg_fn)resolve_orig("sendmsg");
    orig_send = (send_fn)resolve_orig("send");
    orig_recv = (recv_fn)resolve_orig("recv");
    return orig_connect && orig_getaddrinfo && orig_freeaddrinfo &&
           orig_sendto && orig_sendmsg && orig_send && orig_recv;
}

// ---------------------------------------------------------------------------
// Socket helpers
// ---------------------------------------------------------------------------

static bool read_exact(int fd, void *buf, size_t len) {
    uint8_t *p = (uint8_t *)buf;
    while (len > 0) {
        ssize_t n = orig_recv(fd, p, len, 0);
        if (n <= 0) return false;
        p += n;
        len -= (size_t)n;
    }
    return true;
}

static bool write_all(int fd, const void *buf, size_t len) {
    const uint8_t *p = (const uint8_t *)buf;
    while (len > 0) {
        ssize_t n = orig_send(fd, p, len, 0);
        if (n <= 0) return false;
        p += n;
        len -= (size_t)n;
    }
    return true;
}

static int socket_type(int fd) {
    int type = 0;
    socklen_t l = sizeof(type);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &l) != 0) return -1;
    return type;
}

// Base64 (for HTTP Proxy-Authorization). No dependencies.
static std::string base64_encode(const std::string &in) {
    static const char *tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    for (size_t i = 0; i < in.size(); i += 3) {
        uint32_t v = (uint8_t)in[i] << 16;
        if (i + 1 < in.size()) v |= (uint8_t)in[i + 1] << 8;
        if (i + 2 < in.size()) v |= (uint8_t)in[i + 2];
        out += tbl[(v >> 18) & 63];
        out += tbl[(v >> 12) & 63];
        out += (i + 1 < in.size()) ? tbl[(v >> 6) & 63] : '=';
        out += (i + 2 < in.size()) ? tbl[v & 63] : '=';
    }
    return out;
}

// --- SOCKS5 handshake on an already-connected socket ------------------------
// fd is connected to the proxy. host is the target hostname (or "" for a
// numeric-IP target, in which case ip4 is the target, network order).
static bool socks5_handshake(int fd, const std::string &host, uint32_t ip4, int port) {
    uint8_t greet[4];
    greet[0] = 0x05;
    if (!g_cfg.user.empty()) {
        greet[1] = 0x01; greet[2] = 0x02;
        if (!write_all(fd, greet, 3)) return false;
    } else {
        greet[1] = 0x01; greet[2] = 0x00;
        if (!write_all(fd, greet, 3)) return false;
    }
    uint8_t mrep[2];
    if (!read_exact(fd, mrep, 2) || mrep[0] != 0x05) return false;
    if (mrep[1] == 0x02) {
        // Username/password sub-negotiation (RFC 1929).
        std::string auth;
        auth += (char)0x01;
        auth += (char)g_cfg.user.size();
        auth += g_cfg.user;
        auth += (char)g_cfg.pass.size();
        auth += g_cfg.pass;
        if (!write_all(fd, auth.data(), auth.size())) return false;
        uint8_t arep[2];
        if (!read_exact(fd, arep, 2) || arep[0] != 0x01 || arep[1] != 0x00) return false;
    } else if (mrep[1] != 0x00) {
        return false;
    }
    // CONNECT request.
    std::string req;
    req += (char)0x05; req += (char)0x01; req += (char)0x00;
    if (!host.empty()) {
        req += (char)0x03;
        req += (char)host.size();
        req += host;
    } else {
        req += (char)0x01;
        uint32_t be = ip4;
        req.append((const char *)&be, 4);
    }
    req += (char)((port >> 8) & 0xff);
    req += (char)(port & 0xff);
    if (!write_all(fd, req.data(), req.size())) return false;
    // Reply: VER REP RSV ATYP, then address + port.
    uint8_t rep[4];
    if (!read_exact(fd, rep, 4) || rep[0] != 0x05 || rep[1] != 0x00) return false;
    uint8_t atyp = rep[3];
    size_t addr_len = (atyp == 0x01) ? 4 : (atyp == 0x03) ? 0 : (atyp == 0x04) ? 16 : 0;
    if (atyp == 0x03) {
        uint8_t l = 0;
        if (!read_exact(fd, &l, 1)) return false;
        addr_len = l;
    } else if (addr_len == 0) {
        return false;
    }
    std::vector<uint8_t> rest(addr_len + 2);
    if (!read_exact(fd, rest.data(), rest.size())) return false;
    return true;
}

// --- HTTP CONNECT handshake -------------------------------------------------
static bool http_connect_handshake(int fd, const std::string &host, int port) {
    std::string req = "CONNECT " + host + ":" + std::to_string(port) + " HTTP/1.1\r\n";
    req += "Host: " + host + ":" + std::to_string(port) + "\r\n";
    if (!g_cfg.user.empty()) {
        req += "Proxy-Authorization: Basic " + base64_encode(g_cfg.user + ":" + g_cfg.pass) + "\r\n";
    }
    req += "\r\n";
    if (!write_all(fd, req.data(), req.size())) return false;
    // Read headers until \r\n\r\n (cap 8 KB); first line must contain " 200".
    std::string head;
    char c;
    while (head.size() < 8192) {
        ssize_t n = orig_recv(fd, &c, 1, 0);
        if (n <= 0) return false;
        head += c;
        if (head.size() >= 4 && head.compare(head.size() - 4, 4, "\r\n\r\n") == 0) break;
    }
    if (head.size() >= 8192) return false;
    size_t eol = head.find("\r\n");
    if (eol == std::string::npos) return false;
    return head.find(" 200") != std::string::npos && head.find(" 200") < eol;
}

// ---------------------------------------------------------------------------
// Synthetic DNS (mutex-protected; caller must hold g_mu unless noted)
// ---------------------------------------------------------------------------

static bool is_numeric_ip(const char *s) {
    struct in_addr a;
    return s && inet_pton(AF_INET, s, &a) == 1;
}

// Allocate (or reuse) a synthetic address for a hostname. Caller holds g_mu.
// Returns the FULL host-order IPv4 (198.51.100.x), 0 when the table is full.
static uint32_t synth_for_host_locked(const std::string &host) {
    auto it = g_host_to_ip.find(host);
    if (it != g_host_to_ip.end()) return it->second;
    for (int tries = 0; tries < kSyntheticMax; tries++) {
        uint32_t cand = g_next_synth;
        g_next_synth = (g_next_synth % kSyntheticMax) + 1;
        uint32_t full = kSyntheticBase | cand;
        if (g_ip_to_host.find(full) == g_ip_to_host.end()) {
            g_host_to_ip[host] = full;
            g_ip_to_host[full] = host;
            return full;
        }
    }
    return 0;  // table full
}

static int port_from_service(const char *service) {
    if (!service) return 0;
    char *end = NULL;
    long v = strtol(service, &end, 10);
    if (end && *end == '\0' && v > 0 && v < 65536) return (int)v;
    struct servent *se = getservbyname(service, "tcp");
    if (se) return ntohs((uint16_t)se->s_port);
    return 0;
}

// ---------------------------------------------------------------------------
// Hooked functions. These run in the CLONE process only, after install.
// ---------------------------------------------------------------------------

extern "C" int zee_connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    if (!g_active || !addr || addrlen < (socklen_t)sizeof(sa_family_t)) {
        return orig_connect(sockfd, addr, addrlen);
    }
    if (addr->sa_family == AF_INET6) {
        // Fail-closed: no IPv6 outside the proxy.
        errno = EADDRNOTAVAIL;
        return -1;
    }
    if (addr->sa_family != AF_INET) {
        return orig_connect(sockfd, addr, addrlen);
    }
    int type = socket_type(sockfd);
    if (type == SOCK_DGRAM) {
        // Fail-closed: no UDP (QUIC, DNS-over-UDP) outside the proxy.
        errno = EACCES;
        return -1;
    }
    if (type != SOCK_STREAM) {
        return orig_connect(sockfd, addr, addrlen);
    }
    const struct sockaddr_in *in = (const struct sockaddr_in *)addr;
    uint32_t dest_ip = in->sin_addr.s_addr;  // network order
    int dest_port = ntohs(in->sin_port);
    if (g_proxy_ip_ok && dest_ip == g_proxy_ip && dest_port == g_cfg.port) {
        return orig_connect(sockfd, addr, addrlen);  // the proxy itself
    }
    if (!g_proxy_ip_ok) {
        // Proxy host never resolved: fail closed, never leak direct.
        errno = ECONNREFUSED;
        return -1;
    }
    // Resolve the target: synthetic IP -> hostname, else numeric IP.
    std::string host;
    uint32_t ip4 = 0;
    pthread_mutex_lock(&g_mu);
    auto it = g_ip_to_host.find(ntohl(dest_ip));
    if (it != g_ip_to_host.end()) host = it->second;
    pthread_mutex_unlock(&g_mu);
    if (host.empty()) ip4 = dest_ip;

    // Temporarily force blocking mode + bounded timeouts for the handshake,
    // then restore exactly what the app had.
    int flags = fcntl(sockfd, F_GETFL, 0);
    bool was_nonblock = (flags >= 0) && (flags & O_NONBLOCK);
    if (was_nonblock) fcntl(sockfd, F_SETFL, flags & ~O_NONBLOCK);
    struct timeval old_rto = {0, 0}, old_sto = {0, 0};
    socklen_t tl = sizeof(old_rto);
    getsockopt(sockfd, SOL_SOCKET, SO_RCVTIMEO, &old_rto, &tl);
    tl = sizeof(old_sto);
    getsockopt(sockfd, SOL_SOCKET, SO_SNDTIMEO, &old_sto, &tl);
    struct timeval tmo = {15, 0};
    setsockopt(sockfd, SOL_SOCKET, SO_RCVTIMEO, &tmo, sizeof(tmo));
    setsockopt(sockfd, SOL_SOCKET, SO_SNDTIMEO, &tmo, sizeof(tmo));

    struct sockaddr_in proxy_addr;
    memset(&proxy_addr, 0, sizeof(proxy_addr));
    proxy_addr.sin_family = AF_INET;
    proxy_addr.sin_addr.s_addr = g_proxy_ip;
    proxy_addr.sin_port = htons((uint16_t)g_cfg.port);

    bool ok = false;
    if (orig_connect(sockfd, (struct sockaddr *)&proxy_addr, sizeof(proxy_addr)) == 0) {
        if (g_cfg.type == "http") {
            std::string h = host.empty() ? std::string(inet_ntoa(in->sin_addr)) : host;
            ok = http_connect_handshake(sockfd, h, dest_port);
        } else {
            ok = socks5_handshake(sockfd, host, ip4, dest_port);
        }
    }
    // Restore the app's socket state.
    setsockopt(sockfd, SOL_SOCKET, SO_RCVTIMEO, &old_rto, sizeof(old_rto));
    setsockopt(sockfd, SOL_SOCKET, SO_SNDTIMEO, &old_sto, sizeof(old_sto));
    if (was_nonblock) fcntl(sockfd, F_SETFL, flags);
    if (!ok) {
        ZP_LOGD("proxy connect failed: %s:%d via %s proxy",
                host.empty() ? inet_ntoa(in->sin_addr) : host.c_str(), dest_port,
                g_cfg.type.c_str());
        errno = ECONNREFUSED;
        return -1;
    }
    return 0;
}

extern "C" int zee_getaddrinfo(const char *node, const char *service,
                               const struct addrinfo *hints, struct addrinfo **res) {
    if (!g_active || !node || !res) {
        return orig_getaddrinfo(node, service, hints, res);
    }
    if (hints && (hints->ai_flags & AI_NUMERICHOST)) {
        return orig_getaddrinfo(node, service, hints, res);
    }
    if (is_numeric_ip(node)) {
        return orig_getaddrinfo(node, service, hints, res);  // no DNS involved
    }
    if (!g_cfg.host.empty() && strcmp(node, g_cfg.host.c_str()) == 0) {
        return orig_getaddrinfo(node, service, hints, res);  // the proxy itself
    }
    int socktype = hints ? hints->ai_socktype : 0;
    if (socktype != 0 && socktype != SOCK_STREAM) {
        return EAI_SOCKTYPE;  // no UDP/DNS lookups through the proxy path
    }
    if (hints && hints->ai_family == AF_INET6) {
        return EAI_ADDRFAMILY;  // fail-closed: IPv6 is blocked, use IPv4
    }
    int port = port_from_service(service);
    pthread_mutex_lock(&g_mu);
    uint32_t synth = synth_for_host_locked(node);
    if (synth == 0) {
        pthread_mutex_unlock(&g_mu);
        return EAI_AGAIN;
    }
    struct addrinfo *ai = (struct addrinfo *)calloc(1, sizeof(struct addrinfo));
    struct sockaddr_in *sa = (struct sockaddr_in *)calloc(1, sizeof(struct sockaddr_in));
    if (!ai || !sa) {
        free(ai); free(sa);
        pthread_mutex_unlock(&g_mu);
        return EAI_MEMORY;
    }
    sa->sin_family = AF_INET;
    sa->sin_addr.s_addr = htonl(synth);
    sa->sin_port = htons((uint16_t)port);
    ai->ai_flags = 0;
    ai->ai_family = AF_INET;
    ai->ai_socktype = socktype != 0 ? socktype : SOCK_STREAM;
    ai->ai_protocol = (ai->ai_socktype == SOCK_STREAM) ? IPPROTO_TCP : 0;
    ai->ai_addrlen = sizeof(struct sockaddr_in);
    ai->ai_addr = (struct sockaddr *)sa;
    ai->ai_canonname = NULL;
    ai->ai_next = NULL;
    g_synth_results.push_back(ai);
    pthread_mutex_unlock(&g_mu);
    *res = ai;
    return 0;
}

extern "C" void zee_freeaddrinfo(struct addrinfo *res) {
    if (!res) return;
    pthread_mutex_lock(&g_mu);
    for (size_t i = 0; i < g_synth_results.size(); i++) {
        if (g_synth_results[i] == res) {
            g_synth_results.erase(g_synth_results.begin() + (long)i);
            pthread_mutex_unlock(&g_mu);
            free(res->ai_addr);
            free(res->ai_canonname);
            free(res);
            return;
        }
    }
    pthread_mutex_unlock(&g_mu);
    orig_freeaddrinfo(res);
}

static struct hostent *synth_hostent(const char *name, uint32_t synth_ip_host_order) {
    // gethostbyname() returns static storage by contract.
    static thread_local struct hostent he;
    static thread_local struct in_addr addr;
    static thread_local char *aliases[1] = {NULL};
    static thread_local char *addr_list[2] = {NULL, NULL};
    static thread_local char name_buf[256];
    snprintf(name_buf, sizeof(name_buf), "%s", name);
    addr.s_addr = htonl(synth_ip_host_order);
    he.h_name = name_buf;
    he.h_aliases = aliases;
    he.h_addrtype = AF_INET;
    he.h_length = 4;
    addr_list[0] = (char *)&addr;
    he.h_addr_list = addr_list;
    return &he;
}

extern "C" struct hostent *zee_gethostbyname(const char *name) {
    if (!g_active || !name || is_numeric_ip(name) ||
        (!g_cfg.host.empty() && strcmp(name, g_cfg.host.c_str()) == 0)) {
        return orig_gethostbyname(name);
    }
    pthread_mutex_lock(&g_mu);
    uint32_t synth = synth_for_host_locked(name);
    pthread_mutex_unlock(&g_mu);
    if (synth == 0) {
        h_errno = NO_RECOVERY;
        return NULL;
    }
    return synth_hostent(name, synth);
}

extern "C" struct hostent *zee_gethostbyname2(const char *name, int af) {
    if (af == AF_INET6 && g_active) {
        h_errno = NO_RECOVERY;
        return NULL;
    }
    if (af != AF_INET) return orig_gethostbyname2(name, af);
    return zee_gethostbyname(name);
}

// UDP fail-closed: a proxied clone must never send a UDP packet directly.
static bool udp_blocked(int fd) {
    if (!g_active) return false;
    return socket_type(fd) == SOCK_DGRAM;
}

extern "C" ssize_t zee_sendto(int sockfd, const void *buf, size_t len, int flags,
                              const struct sockaddr *dest_addr, socklen_t addrlen) {
    if (udp_blocked(sockfd)) {
        errno = EACCES;
        return -1;
    }
    return orig_sendto(sockfd, buf, len, flags, dest_addr, addrlen);
}

extern "C" ssize_t zee_sendmsg(int sockfd, const struct msghdr *msg, int flags) {
    if (udp_blocked(sockfd)) {
        errno = EACCES;
        return -1;
    }
    return orig_sendmsg(sockfd, msg, flags);
}

extern "C" ssize_t zee_send(int sockfd, const void *buf, size_t len, int flags) {
    if (udp_blocked(sockfd)) {
        errno = EACCES;
        return -1;
    }
    return orig_send(sockfd, buf, len, flags);
}

// ---------------------------------------------------------------------------
// Install
// ---------------------------------------------------------------------------

#ifndef ZEE_PROXY_HOST_TEST
static bool hook_one(const char *name, void *replacement, void **orig_out) {
    void *addr = DobbySymbolResolver("libc.so", name);
    if (!addr) addr = dlsym(RTLD_DEFAULT, name);
    if (!addr) {
        ZP_LOGE("cannot resolve %s", name);
        return false;
    }
    if (DobbyHook(addr, replacement, orig_out) != 0) {
        ZP_LOGE("DobbyHook failed for %s", name);
        return false;
    }
    return true;
}
#endif

static bool install_hooks() {
    if (g_installed) return true;
    if (!capture_origs()) {
        ZP_LOGE("could not capture original libc functions");
        return false;
    }
#ifdef ZEE_PROXY_HOST_TEST
    // Host test: the test binary calls zee_* directly; no Dobby here.
    g_installed = true;
    return true;
#else
    bool ok = true;
    ok &= hook_one("connect", (void *)zee_connect, (void **)&orig_connect);
    ok &= hook_one("getaddrinfo", (void *)zee_getaddrinfo, (void **)&orig_getaddrinfo);
    ok &= hook_one("freeaddrinfo", (void *)zee_freeaddrinfo, (void **)&orig_freeaddrinfo);
    ok &= hook_one("gethostbyname", (void *)zee_gethostbyname, (void **)&orig_gethostbyname);
    ok &= hook_one("gethostbyname2", (void *)zee_gethostbyname2, (void **)&orig_gethostbyname2);
    ok &= hook_one("sendto", (void *)zee_sendto, (void **)&orig_sendto);
    ok &= hook_one("sendmsg", (void *)zee_sendmsg, (void **)&orig_sendmsg);
    ok &= hook_one("send", (void *)zee_send, (void **)&orig_send);
    g_installed = ok;
    return ok;
#endif
}

// Resolve the proxy host ONCE, before the hooks go in (its name is the only
// one ever resolved for real). Returns false when it cannot be resolved —
// the hooks still go in and every connect() then fails closed.
static bool resolve_proxy() {
    g_proxy_ip_ok = false;
    if (g_cfg.host.empty() || g_cfg.port <= 0) return false;
    struct in_addr a;
    if (inet_pton(AF_INET, g_cfg.host.c_str(), &a) == 1) {
        g_proxy_ip = a.s_addr;
        g_proxy_ip_ok = true;
        return true;
    }
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo *res = NULL;
    if (orig_getaddrinfo(g_cfg.host.c_str(), NULL, &hints, &res) == 0 && res) {
        struct sockaddr_in *in = (struct sockaddr_in *)res->ai_addr;
        g_proxy_ip = in->sin_addr.s_addr;
        g_proxy_ip_ok = true;
        orig_freeaddrinfo(res);
        return true;
    }
    return false;
}

static void enable_from_config(const ProxyConfig &cfg, const std::string &status_path) {
    g_cfg = cfg;
    g_status_path = status_path;
    g_have_cfg = true;
    if (!cfg.enabled) {
        write_status("enabled=0\nreason=proxy disabled for this clone\n");
        ZP_LOGD("proxy disabled for this clone");
        return;
    }
    if (cfg.host.empty() || cfg.port <= 0 || cfg.port > 65535) {
        write_status("enabled=0\nreason=bad proxy host/port in config\n");
        ZP_LOGE("bad proxy host/port in config");
        return;
    }
    if (cfg.type != "socks5" && cfg.type != "http") {
        write_status("enabled=0\nreason=unknown proxy type (want socks5|http)\n");
        ZP_LOGE("unknown proxy type: %s", cfg.type.c_str());
        return;
    }
    if (!install_hooks()) {
        write_status("enabled=0\nreason=hook install failed\n");
        ZP_LOGE("hook install failed");
        return;
    }
    bool resolved = resolve_proxy();
    g_active = true;
    std::string st = "enabled=1\ntype=" + cfg.type + "\nhost=" + cfg.host +
                     "\nport=" + std::to_string(cfg.port) +
                     "\ncountry=" + cfg.country +
                     "\nproxy_resolved=" + (resolved ? "1" : "0") + "\n";
    if (!resolved) st += "reason=proxy host did not resolve; all connects fail closed\n";
    write_status(st);
    ZP_LOGD("proxy active: %s %s:%d country=%s resolved=%d",
            cfg.type.c_str(), cfg.host.c_str(), cfg.port, cfg.country.c_str(), (int)resolved);
}

#ifndef ZEE_PROXY_HOST_TEST
extern "C" void ZeeProxy_Enable(JNIEnv *env, jclass /*clazz*/, jstring packageName,
                                jint userId, jstring hostFilesDir) {
    try {
        if (!env || !packageName || !hostFilesDir) return;
        const char *pkg = env->GetStringUTFChars(packageName, NULL);
        const char *dir = env->GetStringUTFChars(hostFilesDir, NULL);
        if (!pkg || !dir) {
            if (pkg) env->ReleaseStringUTFChars(packageName, pkg);
            if (dir) env->ReleaseStringUTFChars(hostFilesDir, dir);
            return;
        }
        char cfg_path[1024];
        char status_path[1024];
        snprintf(cfg_path, sizeof(cfg_path), "%s/proxy/%s_%d.json", dir, pkg, (int)userId);
        snprintf(status_path, sizeof(status_path), "%s/proxy/%s_%d.status", dir, pkg, (int)userId);
        std::string js = read_file(cfg_path);
        env->ReleaseStringUTFChars(packageName, pkg);
        env->ReleaseStringUTFChars(hostFilesDir, dir);
        if (js.empty()) {
            // No proxy configured for this clone: nothing to do.
            return;
        }
        ProxyConfig cfg;
        json_bool(js, "enabled", cfg.enabled);
        json_string(js, "type", cfg.type);
        json_string(js, "host", cfg.host);
        json_int(js, "port", cfg.port);
        json_string(js, "user", cfg.user);
        json_string(js, "pass", cfg.pass);
        json_string(js, "country", cfg.country);
        enable_from_config(cfg, status_path);
    } catch (...) {
        ZP_LOGE("ZeeProxy_Enable threw (swallowed)");
    }
}
#else
// Host-test entry: configure directly, resolve 127.0.0.1, activate.
extern "C" bool ZeeProxy_TestInit(const char *type, const char *host, int port,
                                  const char *user, const char *pass) {
    ProxyConfig cfg;
    cfg.enabled = true;
    cfg.type = type ? type : "socks5";
    cfg.host = host ? host : "";
    cfg.port = port;
    cfg.user = user ? user : "";
    cfg.pass = pass ? pass : "";
    cfg.country = "TEST";
    g_cfg = cfg;
    g_have_cfg = true;
    if (!install_hooks()) return false;
    bool resolved = resolve_proxy();
    g_active = true;
    ZP_LOGD("test proxy active: %s %s:%d resolved=%d",
            cfg.type.c_str(), cfg.host.c_str(), cfg.port, (int)resolved);
    return resolved;
}
#endif
