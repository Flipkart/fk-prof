#include "ctx_switch_tracer.hh"

#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>

CtxSwitchTracer::CtxSwitchTracer(ThreadMap& _thread_map, ProfileSerializingWriter& _serializer, std::uint32_t _max_stack_depth,
                                 ProbPct& _prob_pct, std::uint8_t _noctx_cov_pct, std::uint64_t _latency_threshold_ns,
                                 bool _track_wakeup_lag, bool _use_global_clock, bool _track_syscall, const char* listener_socket_path,
                                 const char* proc) {
    try {
        connect_tracer(listener_socket_path, proc);
    } catch(...) {
        close(trace_conn);
        throw;
    }
}

CtxSwitchTracer::~CtxSwitchTracer() {}

void CtxSwitchTracer::start(JNIEnv* env) {}

void CtxSwitchTracer::run() {}

void CtxSwitchTracer::stop() {}

void CtxSwitchTracer::connect_tracer(const char* listener_socket_path, const char* proc) {
    trace_conn = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (trace_conn < 0) throw log_and_get_error("Couldn't create client-socket for tracing", errno);
    
    sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::string client_path = std::string("/tmp/fkp-trace.") + proc + ".client.sock";
    std::strncpy(addr.sun_path, client_path.c_str(), sizeof(addr.sun_path));

    if (Util::file_exists(client_path.c_str())) {
        logger->info("Client socket file {} exists, will try to unlink", client_path);
        if (unlink(client_path.c_str()) != 0) {
            throw log_and_get_error("Couldn't unlink tracing client-socket", errno);
        }
    }
    
    auto ret = bind(trace_conn, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (ret != 0) throw log_and_get_error("Couldn't bind tracing client-socket", errno);

    std::strncpy(addr.sun_path, listener_socket_path, sizeof(addr.sun_path));
    ret = connect(trace_conn, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (ret != 0) throw log_and_get_error("Couldn't start listening", errno);
}
