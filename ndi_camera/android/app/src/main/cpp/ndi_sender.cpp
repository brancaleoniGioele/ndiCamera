#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstring>
#include <cstdint>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#include <net/if.h>
#include <ifaddrs.h>
#include <fcntl.h>
#include <errno.h>

#define LOG_TAG "NdiSender"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Costanti protocollo NDI ──────────────────────────────────────────────────

// NDI usa una versione semplificata di mDNS per l'annuncio.
// Il tipo di servizio registrato è "_ndi._tcp.local."
static const char* NDI_SERVICE_TYPE = "_ndi._tcp.local.";
static const uint16_t MDNS_PORT     = 5353;
static const char*    MDNS_ADDR     = "224.0.0.251";

// Porta TCP su cui accettiamo connessioni dai receiver NDI.
// Usiamo 0 (assegnazione automatica) e poi leggiamo la porta assegnata.
static const int NDI_TCP_PORT_HINT  = 5960;

// Magic numbers dell'header NDI video frame (protocollo NDI versione 3)
static const uint32_t NDI_MAGIC     = 0x4E444900; // "NDI\0"
static const uint32_t NDI_VERSION   = 3;

// ─── Strutture header NDI ─────────────────────────────────────────────────────

#pragma pack(push, 1)

// Header del pacchetto NDI video
struct NdiVideoHeader {
    uint32_t magic;           // NDI_MAGIC
    uint32_t version;         // NDI_VERSION
    uint32_t frame_type;      // 1 = video, 2 = audio, 3 = metadata
    uint32_t data_length;     // lunghezza payload in bytes
    uint32_t width;
    uint32_t height;
    uint32_t fourcc;          // formato pixel: 'UYVY' = 0x59565955
    uint32_t frame_rate_n;    // numeratore fps
    uint32_t frame_rate_d;    // denominatore fps
    float    aspect_ratio;    // es. 1.777778 per 16:9
    uint32_t flags;           // 0 = progressive
    int64_t  timecode;        // timestamp in unità 100ns (stile NDI)
};

// Pacchetto mDNS minimale per annunciare il servizio NDI
struct MdnsHeader {
    uint16_t id;
    uint16_t flags;
    uint16_t qdcount;
    uint16_t ancount;
    uint16_t nscount;
    uint16_t arcount;
};

#pragma pack(pop)

// ─── Stato globale del sender ─────────────────────────────────────────────────

struct NdiState {
    std::string  source_name;
    std::string  local_ip;
    int          tcp_port     = 0;
    int          server_fd    = -1;   // socket TCP in ascolto
    int          client_fd    = -1;   // receiver connesso
    int          mdns_fd      = -1;   // socket UDP per mDNS
    std::atomic<bool> running {false};
    std::mutex   frame_mutex;
    std::vector<uint8_t> pending_frame; // frame UYVY pronto da inviare
    uint32_t     width        = 0;
    uint32_t     height       = 0;
    uint32_t     fps          = 60;
    std::thread  accept_thread;
    std::thread  mdns_thread;
};

static NdiState g_state;

// ─── Utilità rete ─────────────────────────────────────────────────────────────

static std::string get_local_ip() {
    struct ifaddrs* ifas = nullptr;
    if (getifaddrs(&ifas) != 0) return "0.0.0.0";

    std::string result = "0.0.0.0";
    for (struct ifaddrs* ifa = ifas; ifa != nullptr; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr) continue;
        if (ifa->ifa_addr->sa_family != AF_INET) continue;

        std::string name(ifa->ifa_name);
        // Preferiamo wlan0 (WiFi) su Android
        if (name.find("wlan") == std::string::npos &&
            name.find("eth")  == std::string::npos) continue;

        char buf[INET_ADDRSTRLEN];
        auto* sin = reinterpret_cast<struct sockaddr_in*>(ifa->ifa_addr);
        inet_ntop(AF_INET, &sin->sin_addr, buf, sizeof(buf));
        result = buf;
        if (name.find("wlan") != std::string::npos) break; // wlan ha priorità
    }

    freeifaddrs(ifas);
    return result;
}

// ─── Conversione YUV_420_888 → UYVY packed ───────────────────────────────────
//
// Camera2 su Android produce YUV_420_888 (planar: Y, U, V separati).
// NDI si aspetta UYVY packed: [U0 Y0 V0 Y1] per ogni coppia di pixel.
// La conversione è semplice ma deve gestire i row stride di Camera2.

static std::vector<uint8_t> yuv420_to_uyvy(
    const uint8_t* y_plane,  int y_row_stride,
    const uint8_t* u_plane,  const uint8_t* v_plane,
    int uv_row_stride, int uv_pixel_stride,
    int width, int height)
{
    std::vector<uint8_t> uyvy(width * height * 2);
    uint8_t* dst = uyvy.data();

    for (int row = 0; row < height; ++row) {
        const uint8_t* y_row  = y_plane + row * y_row_stride;
        const uint8_t* uv_row = u_plane + (row / 2) * uv_row_stride;
        const uint8_t* vv_row = v_plane + (row / 2) * uv_row_stride;

        for (int col = 0; col < width; col += 2) {
            int uv_idx = (col / 2) * uv_pixel_stride;

            uint8_t u = uv_row[uv_idx];
            uint8_t v = vv_row[uv_idx];
            uint8_t y0 = y_row[col];
            uint8_t y1 = y_row[col + 1];

            // UYVY: U, Y0, V, Y1
            *dst++ = u;
            *dst++ = y0;
            *dst++ = v;
            *dst++ = y1;
        }
    }

    return uyvy;
}

// ─── mDNS announce ────────────────────────────────────────────────────────────
//
// Inviamo un DNS-SD PTR record periodicamente su 224.0.0.251:5353
// così i receiver NDI sulla stessa rete trovano la sorgente.

static void build_mdns_announce(
    std::vector<uint8_t>& out,
    const std::string& name,
    const std::string& ip,
    uint16_t port)
{
    out.clear();

    // Header mDNS (risposta, authoritative)
    MdnsHeader hdr{};
    hdr.id      = 0;
    hdr.flags   = htons(0x8400); // QR=1, AA=1
    hdr.qdcount = 0;
    hdr.ancount = htons(3); // PTR + SRV + A record
    hdr.nscount = 0;
    hdr.arcount = 0;

    auto push16 = [&](uint16_t v) {
        out.push_back((v >> 8) & 0xFF);
        out.push_back(v & 0xFF);
    };
    auto push32 = [&](uint32_t v) {
        out.push_back((v >> 24) & 0xFF);
        out.push_back((v >> 16) & 0xFF);
        out.push_back((v >>  8) & 0xFF);
        out.push_back(v & 0xFF);
    };
    auto push_label = [&](const std::string& s) {
        // Encode DNS name: split su '.' e prefissa lunghezza
        size_t start = 0;
        while (start < s.size()) {
            size_t dot = s.find('.', start);
            if (dot == std::string::npos) dot = s.size();
            std::string part = s.substr(start, dot - start);
            out.push_back((uint8_t)part.size());
            for (char c : part) out.push_back((uint8_t)c);
            start = dot + 1;
        }
        out.push_back(0); // terminatore
    };

    // Header come bytes
    for (size_t i = 0; i < sizeof(hdr); i++)
        out.push_back(reinterpret_cast<uint8_t*>(&hdr)[i]);

    // --- Record 1: PTR ---
    // _ndi._tcp.local. → <name>._ndi._tcp.local.
    push_label("_ndi._tcp.local");
    push16(12);    // TYPE PTR
    push16(1);     // CLASS IN
    push32(120);   // TTL 120s

    std::string full_name = name + "._ndi._tcp.local";
    uint16_t rdata_len = 1 + (uint16_t)full_name.size() + 2; // length approx
    // Encode rdata (il nome puntato)
    std::vector<uint8_t> rdata_buf;
    {
        size_t start = 0;
        while (start < full_name.size()) {
            size_t dot = full_name.find('.', start);
            if (dot == std::string::npos) dot = full_name.size();
            std::string part = full_name.substr(start, dot - start);
            rdata_buf.push_back((uint8_t)part.size());
            for (char c : part) rdata_buf.push_back((uint8_t)c);
            start = dot + 1;
        }
        rdata_buf.push_back(0);
    }
    push16((uint16_t)rdata_buf.size());
    for (uint8_t b : rdata_buf) out.push_back(b);

    // --- Record 2: SRV ---
    push_label(name + "._ndi._tcp.local");
    push16(33);   // TYPE SRV
    push16(1);    // CLASS IN
    push32(120);  // TTL

    std::string host = name + ".local";
    std::vector<uint8_t> srv_target;
    {
        size_t start = 0;
        while (start < host.size()) {
            size_t dot = host.find('.', start);
            if (dot == std::string::npos) dot = host.size();
            std::string part = host.substr(start, dot - start);
            srv_target.push_back((uint8_t)part.size());
            for (char c : part) srv_target.push_back((uint8_t)c);
            start = dot + 1;
        }
        srv_target.push_back(0);
    }
    push16((uint16_t)(6 + srv_target.size())); // priority + weight + port + target
    push16(0);    // priority
    push16(0);    // weight
    push16(port); // porta TCP
    for (uint8_t b : srv_target) out.push_back(b);

    // --- Record 3: A record (IP) ---
    push_label(name + ".local");
    push16(1);    // TYPE A
    push16(1);    // CLASS IN
    push32(120);  // TTL
    push16(4);    // RDLENGTH = 4 bytes per IPv4

    struct in_addr addr{};
    inet_pton(AF_INET, ip.c_str(), &addr);
    uint32_t ip_n = addr.s_addr;
    out.push_back((ip_n      ) & 0xFF);
    out.push_back((ip_n >>  8) & 0xFF);
    out.push_back((ip_n >> 16) & 0xFF);
    out.push_back((ip_n >> 24) & 0xFF);
}

static void mdns_loop() {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) { LOGE("mdns socket failed: %s", strerror(errno)); return; }

    int yes = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &yes, sizeof(yes));

    // Multicast TTL = 255 (link-local)
    uint8_t ttl = 255;
    setsockopt(fd, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, sizeof(ttl));

    struct sockaddr_in dest{};
    dest.sin_family      = AF_INET;
    dest.sin_port        = htons(MDNS_PORT);
    inet_pton(AF_INET, MDNS_ADDR, &dest.sin_addr);

    g_state.mdns_fd = fd;

    // Annuncia ogni 1 secondo finché running
    while (g_state.running.load()) {
        std::vector<uint8_t> pkt;
        build_mdns_announce(pkt,
            g_state.source_name,
            g_state.local_ip,
            (uint16_t)g_state.tcp_port);

        sendto(fd, pkt.data(), pkt.size(), 0,
               reinterpret_cast<struct sockaddr*>(&dest), sizeof(dest));

        LOGI("mDNS announce sent: %s @ %s:%d",
             g_state.source_name.c_str(),
             g_state.local_ip.c_str(),
             g_state.tcp_port);

        // Sleep 1s a pezzetti per reagire al flag running
        for (int i = 0; i < 10 && g_state.running.load(); i++)
            usleep(100'000);
    }

    close(fd);
    g_state.mdns_fd = -1;
}

// ─── TCP server — accetta receiver e invia frame ──────────────────────────────

static bool send_all(int fd, const uint8_t* data, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = ::send(fd, data + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) return false;
        sent += n;
    }
    return true;
}

static void accept_loop() {
    // Il client_fd viene scritto dall'accept; poi inviamo frame in questa stessa thread
    while (g_state.running.load()) {

        // Aspetta connessione (con timeout per poter uscire)
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(g_state.server_fd, &fds);
        struct timeval tv{1, 0}; // 1 secondo
        int sel = select(g_state.server_fd + 1, &fds, nullptr, nullptr, &tv);

        if (sel <= 0) continue; // timeout o errore

        struct sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);
        int cfd = accept(g_state.server_fd,
                         reinterpret_cast<struct sockaddr*>(&client_addr),
                         &addr_len);
        if (cfd < 0) {
            LOGE("accept failed: %s", strerror(errno));
            continue;
        }

        char client_ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &client_addr.sin_addr, client_ip, sizeof(client_ip));
        LOGI("NDI receiver connected: %s", client_ip);

        // Chiudi eventuale client precedente
        if (g_state.client_fd >= 0) {
            close(g_state.client_fd);
        }
        g_state.client_fd = cfd;

        // Loop invio frame per questo client
        while (g_state.running.load()) {
            std::vector<uint8_t> frame;
            uint32_t w, h;

            {
                std::lock_guard<std::mutex> lk(g_state.frame_mutex);
                if (g_state.pending_frame.empty()) {
                    // Nessun frame nuovo, aspetta un po'
                }
                frame = g_state.pending_frame;
                w = g_state.width;
                h = g_state.height;
            }

            if (frame.empty()) {
                usleep(1000); // 1ms
                continue;
            }

            // Costruisci header NDI video
            NdiVideoHeader hdr{};
            hdr.magic         = htonl(NDI_MAGIC);
            hdr.version       = htonl(NDI_VERSION);
            hdr.frame_type    = htonl(1); // video
            hdr.data_length   = htonl((uint32_t)frame.size());
            hdr.width         = htonl(w);
            hdr.height        = htonl(h);
            hdr.fourcc        = htonl(0x59565955); // 'UYVY'
            hdr.frame_rate_n  = htonl(g_state.fps);
            hdr.frame_rate_d  = htonl(1);
            hdr.aspect_ratio  = (float)w / (float)h;
            hdr.flags         = 0; // progressive
            hdr.timecode      = 0;

            // Invia header + payload
            bool ok = send_all(cfd,
                               reinterpret_cast<const uint8_t*>(&hdr),
                               sizeof(hdr));
            if (ok) ok = send_all(cfd, frame.data(), frame.size());

            if (!ok) {
                LOGI("NDI receiver disconnected");
                break;
            }
        }

        close(cfd);
        g_state.client_fd = -1;
    }
}

// ─── JNI entry points ─────────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_ndi_1camera_NdiSender_init(
    JNIEnv* env, jobject /* thiz */,
    jstring source_name_j)
{
    const char* name = env->GetStringUTFChars(source_name_j, nullptr);
    g_state.source_name = name;
    env->ReleaseStringUTFChars(source_name_j, name);

    g_state.local_ip = get_local_ip();
    LOGI("Local IP: %s", g_state.local_ip.c_str());

    // Crea socket TCP in ascolto
    g_state.server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (g_state.server_fd < 0) {
        LOGE("TCP socket failed: %s", strerror(errno));
        return JNI_FALSE;
    }

    int yes = 1;
    setsockopt(g_state.server_fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    struct sockaddr_in addr{};
    addr.sin_family      = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port        = htons(NDI_TCP_PORT_HINT);

    if (bind(g_state.server_fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        // Se la porta è occupata, lascia scegliere al kernel
        addr.sin_port = 0;
        bind(g_state.server_fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr));
    }

    // Leggi la porta effettiva assegnata
    socklen_t len = sizeof(addr);
    getsockname(g_state.server_fd, reinterpret_cast<struct sockaddr*>(&addr), &len);
    g_state.tcp_port = ntohs(addr.sin_port);
    LOGI("NDI TCP listening on port %d", g_state.tcp_port);

    if (listen(g_state.server_fd, 1) < 0) {
        LOGE("listen failed: %s", strerror(errno));
        close(g_state.server_fd);
        g_state.server_fd = -1;
        return JNI_FALSE;
    }

    g_state.running = true;

    // Avvia thread mDNS e accept
    g_state.mdns_thread   = std::thread(mdns_loop);
    g_state.accept_thread = std::thread(accept_loop);

    LOGI("NdiSender initialized: %s", g_state.source_name.c_str());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_ndi_1camera_NdiSender_sendFrame(
    JNIEnv* env, jobject /* thiz */,
    jbyteArray y_arr, jbyteArray u_arr, jbyteArray v_arr,
    jint y_row_stride, jint uv_row_stride, jint uv_pixel_stride,
    jint width, jint height,
    jlong timestamp_us)
{
    if (!g_state.running.load()) return JNI_FALSE;

    jsize y_len = env->GetArrayLength(y_arr);
    jsize u_len = env->GetArrayLength(u_arr);
    jsize v_len = env->GetArrayLength(v_arr);

    std::vector<uint8_t> y_buf(y_len), u_buf(u_len), v_buf(v_len);
    env->GetByteArrayRegion(y_arr, 0, y_len, reinterpret_cast<jbyte*>(y_buf.data()));
    env->GetByteArrayRegion(u_arr, 0, u_len, reinterpret_cast<jbyte*>(u_buf.data()));
    env->GetByteArrayRegion(v_arr, 0, v_len, reinterpret_cast<jbyte*>(v_buf.data()));

    auto uyvy = yuv420_to_uyvy(
        y_buf.data(), y_row_stride,
        u_buf.data(), v_buf.data(),
        uv_row_stride, uv_pixel_stride,
        width, height);

    {
        std::lock_guard<std::mutex> lk(g_state.frame_mutex);
        g_state.pending_frame = std::move(uyvy);
        g_state.width  = (uint32_t)width;
        g_state.height = (uint32_t)height;
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_ndi_1camera_NdiSender_release(
    JNIEnv* /* env */, jobject /* thiz */)
{
    g_state.running = false;

    if (g_state.client_fd >= 0) { close(g_state.client_fd); g_state.client_fd = -1; }
    if (g_state.server_fd >= 0) { close(g_state.server_fd); g_state.server_fd = -1; }
    if (g_state.mdns_fd   >= 0) { close(g_state.mdns_fd);   g_state.mdns_fd   = -1; }

    if (g_state.accept_thread.joinable()) g_state.accept_thread.join();
    if (g_state.mdns_thread.joinable())   g_state.mdns_thread.join();

    g_state.pending_frame.clear();
    LOGI("NdiSender released");
}

JNIEXPORT jstring JNICALL
Java_com_example_ndi_1camera_NdiSender_getLocalIp(
    JNIEnv* env, jobject /* thiz */)
{
    return env->NewStringUTF(g_state.local_ip.c_str());
}

} // extern "C"