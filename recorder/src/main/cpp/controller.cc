#include "controller.hh"
#include <chrono>
#include <curl/curl.h>
#include "buff.hh"
#include "blocking_ring_buffer.hh"

void controllerRunnable(jvmtiEnv *jvmti_env, JNIEnv *jni_env, void *arg) {
    auto control = static_cast<Controller*>(arg);
    control->run();
}

void write_system_prop(jvmtiEnv* env, const char* name, std::stringstream& ss) {
    char* value;
    if (env->GetSystemProperty(name, &value) == JVMTI_ERROR_NONE) {
        ss << value << "; ";
        env->Deallocate((unsigned char *) value);
    }
}

std::string load_vm_id(jvmtiEnv* env) {
    std::stringstream ss;
    write_system_prop(env, "java.vm.name", ss);
    write_system_prop(env, "java.vm.specification.version", ss);
    write_system_prop(env, "java.vm.version", ss);
    write_system_prop(env, "java.vm.info", ss);
    write_system_prop(env, "java.vm.vendor", ss);
    return ss.str();
}

Controller::Controller(JavaVM *_jvm, jvmtiEnv *_jvmti, ThreadMap& _thread_map, ConfigurationOptions& _cfg) :
    jvm(_jvm), jvmti(_jvmti), thread_map(_thread_map), cfg(_cfg), keep_running(false), writer(nullptr),
    serializer(nullptr), processor(nullptr), raw_writer_ring(_cfg.tx_ring_sz),

    s_t_poll_rpc(get_metrics_registry().new_timer({METRICS_DOMAIN, METRICS_TYPE_RPC, "poll"})),
    s_c_poll_rpc_failures(get_metrics_registry().new_counter({METRICS_DOMAIN, METRICS_TYPE_RPC, "poll", "failures"})),

    s_t_associate_rpc(get_metrics_registry().new_timer({METRICS_DOMAIN, METRICS_TYPE_RPC, "associate"})),
    s_c_associate_rpc_failures(get_metrics_registry().new_counter({METRICS_DOMAIN, METRICS_TYPE_RPC, "associate", "failures"})),

    s_v_work_cpu_sampling(get_metrics_registry().new_value({METRICS_DOMAIN, METRICS_TYPE_STATE, "working", "cpu_sampling"})),

    s_c_work_success(get_metrics_registry().new_counter({METRICS_DOMAIN, "work", "retire", "success"})),
    s_c_work_failure(get_metrics_registry().new_counter({METRICS_DOMAIN, "work", "retire", "failure"})),
    s_c_work_retired(get_metrics_registry().new_counter({METRICS_DOMAIN, "work", "retired"})) {

    current_work.set_work_id(0);
    current_work_state = recording::WorkResponse::complete;
    current_work_result = recording::WorkResponse::success;
    cancel_work = []{};
    vm_id = load_vm_id(jvmti);
}

void Controller::start() {
    keep_running.store(true, std::memory_order_relaxed);
    thd_proc = start_new_thd(jvm, jvmti, "Fk-Prof Controller Thread", controllerRunnable, this);
}

void Controller::stop() {
    cancel_work();
    scheduler.schedule(Time::now(), [&]() {
            //This is necessary because it wakes up the scheduler.poll and makes keep_running == false
            //  We can either schedule something or we'd need additional stop-control in scheduler.
            //  This hack (in a good way) simplifies scheduler.
            keep_running.store(false, std::memory_order_relaxed);
        });
    await_thd_death(thd_proc);
    thd_proc.reset();
}

bool Controller::is_running() const {
    return keep_running.load();
}

struct CurlInit {
    CurlInit() {
        curl_global_init(CURL_GLOBAL_ALL);
    }
    ~CurlInit() {
        curl_global_cleanup();
    }
};

static void time_now_str(std::function<void(const char*)> fn) {
    std::time_t t = std::time(nullptr);
    std::tm tm = *std::localtime(&t);
    char buffer[120];
    strftime(buffer, sizeof(buffer),"%Y-%m-%dT%H:%M:%S%z", &tm);
    fn(buffer);
}

static void populate_recorder_info(recording::RecorderInfo& ri, const ConfigurationOptions& cfg, const std::string& vm_id, const Time::Pt& start_time, std::uint64_t poll_tick) {
    ri.set_ip(cfg.ip);
    ri.set_hostname(cfg.host);
    ri.set_app_id(cfg.app_id);
    ri.set_instance_grp(cfg.inst_grp);
    ri.set_cluster(cfg.cluster);
    ri.set_instance_id(cfg.inst_id);
    ri.set_proc_name(cfg.proc);
    ri.set_vm_id(vm_id + cfg.vm_id);
    ri.set_zone(cfg.zone);
    ri.set_instance_type(cfg.inst_typ);
    time_now_str([&ri](std::string now) {
            ri.set_local_time(now);
        });
    ri.set_recorder_version(RECORDER_VERION);
    ri.set_recorder_tick(poll_tick);
    auto now = Time::now();
    std::chrono::duration<double> uptime = now - start_time;
    ri.set_recorder_uptime(uptime.count());

    auto c = ri.mutable_capabilities();
    c->set_can_cpu_sample(cfg.allow_sigprof);
}

static void populate_issued_work_status(recording::WorkResponse& w_res, std::uint64_t work_id, recording::WorkResponse::WorkState state, recording::WorkResponse::WorkResult result, Time::Pt& start_tm, Time::Pt& end_tm) {
    w_res.set_work_id(work_id);
    w_res.set_work_state(state);
    w_res.set_work_result(result);
    std::uint32_t elapsed_time = 0;
    switch(state) {
    case recording::WorkResponse::running:
        elapsed_time = Time::elapsed_seconds(Time::now(), start_tm);
        break;
    case recording::WorkResponse::complete:
        elapsed_time = Time::elapsed_seconds(end_tm, start_tm);
        break;
    case recording::WorkResponse::pre_start:
        break; //already initialized
    default:
        logger->error("Unexpected work-state {} found", state);
    }
    w_res.set_elapsed_time(elapsed_time);
}

typedef std::unique_ptr<CURL, void(*)(CURL*)> Curl;
typedef std::unique_ptr<struct curl_slist, void(*)(curl_slist*)> CurlHeader;

static std::uint32_t backoff(std::uint32_t& seconds, std::uint32_t multiplier, std::uint32_t max_backoff_val) {
    auto current_backoff = seconds;
    logger->error("COMM failed, backing-off by {} seconds", seconds);
    seconds = Util::min(seconds * multiplier, max_backoff_val);
    return current_backoff;
}

static int write_to_curl_request(char *out_buff, size_t size, size_t nitems, void *send_buff) {
    auto buff = static_cast<Buff*>(send_buff);
    std::uint32_t out_capacity = size * nitems;
    auto should_copy = Util::min(out_capacity, buff->write_end - buff->read_end);
    std::memcpy(out_buff, buff->buff + buff->read_end, should_copy);
    buff->read_end += should_copy;
    return should_copy;
}

static int read_from_curl_response(char *in_buff, size_t size, size_t nmemb, void *recv_buff) {
    auto buff = static_cast<Buff*>(recv_buff);
    auto available = size * nmemb;
    buff->ensure_free(available);
    memcpy(buff->buff + buff->write_end, in_buff, available);
    buff->write_end += available;
    return available;
}

static CurlHeader make_header_list(const std::vector<const char*>& headers) {
    CurlHeader header_list(nullptr, curl_slist_free_all);
    for(auto hdr : headers) {
        auto new_head = curl_slist_append(header_list.get(), hdr);
        if (new_head != nullptr) {
            header_list.release();
            header_list.reset(new_head);
        }
    }
    return header_list;
}

static CurlHeader default_http_req_headers() {
    return make_header_list({"Content-type: application/octet-stream", "Transfer-Encoding: chunked"});
}

static inline bool do_call(Curl& curl, const char* url, const char* functional_area, std::uint32_t retries_used, metrics::Timer& timer, metrics::Ctr& fail_ctr) {
    auto timer_ctx = timer.time_scope();
    curl_easy_setopt(curl.get(), CURLOPT_URL, url);
    auto res = curl_easy_perform(curl.get());
    long http_code = -1;
    if (res == CURLE_OK) {
        curl_easy_getinfo (curl.get(), CURLINFO_RESPONSE_CODE, &http_code);
        if (http_code >= 200 && http_code < 300) {
            return true;
        }
    }
    auto curl_err_str = curl_easy_strerror(res);
    logger->error("COMM Couldn't talk to {} (for {}) (error({}): {}, http-status: {}, retries-used: {})", url, functional_area, res, curl_err_str, http_code, retries_used);
    fail_ctr.inc();
    return false;
}

void Controller::run() {
    auto start_time = Time::now();
    CurlInit _;
    Curl curl(curl_easy_init(), curl_easy_cleanup);
    CurlHeader header_list(default_http_req_headers());
    Buff send(1024);
    Buff recv(1024);

    if (curl.get() == nullptr || header_list == nullptr) {
        logger->error("Controller initialization failed because cURL init failed");
        return;
    }
    curl_easy_setopt(curl.get(), CURLOPT_HTTPHEADER, header_list.get());
    curl_easy_setopt(curl.get(), CURLOPT_POST, 1L);
    curl_easy_setopt(curl.get(), CURLOPT_READDATA, &send);
    curl_easy_setopt(curl.get(), CURLOPT_READFUNCTION, write_to_curl_request);
    curl_easy_setopt(curl.get(), CURLOPT_WRITEDATA, &recv);
    curl_easy_setopt(curl.get(), CURLOPT_WRITEFUNCTION, read_from_curl_response);
    curl_easy_setopt(curl.get(), CURLOPT_TIMEOUT, cfg.rpc_timeout);

    std::function<void()> poll_cb, assoc_cb;

    // setup things necessary for POLL
    std::string poll_url, poll_host;
    std::uint32_t poll_port;
    auto poll_backoff_seconds = cfg.backoff_start;
    auto poll_retries_used = 0;
    std::uint64_t poll_tick = 0;
    poll_cb = [&]() {
        recording::PollReq p_req;
        populate_recorder_info(*p_req.mutable_recorder_info(), cfg, vm_id, start_time, poll_tick);

        with_current_work([&p_req](Controller::W& w, Controller::WSt& wst, Controller::WRes& wres, Time::Pt& start_tm, Time::Pt& end_tm) {
                populate_issued_work_status(*p_req.mutable_work_last_issued(), w.work_id(), wst, wres, start_tm, end_tm);
            });

        auto serialized_size = p_req.ByteSize();
        send.ensure_free(serialized_size);
        p_req.SerializeToArray(send.buff, send.capacity);
        send.read_end = 0;
        send.write_end = serialized_size;
        curl_easy_setopt(curl.get(), CURLOPT_INFILESIZE, serialized_size);
        recv.read_end = recv.write_end = 0;

        logger->trace("Polling now");

        auto next_tick = Time::now();
        if (do_call(curl, poll_url.c_str(), "associate-poll", poll_retries_used, s_t_poll_rpc, s_c_poll_rpc_failures)) {
            accept_work(recv, poll_host, poll_port);
            poll_backoff_seconds = cfg.backoff_start;
            poll_retries_used = 0;
            next_tick += Time::sec(cfg.poll_itvl);
            poll_tick = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
        } else {
            if (poll_retries_used++ >= cfg.max_retries) {
                // old associate seems gone for good, let us find a new one
                logger->error("COMM failed too many times, giving up on the associate: {}", poll_url);
                poll_retries_used = 0;
                poll_backoff_seconds = cfg.backoff_start;
                scheduler.schedule(Time::now(), assoc_cb);
                return;
            }
            next_tick += Time::sec(backoff(poll_backoff_seconds, cfg.backoff_multiplier, cfg.backoff_max));
        }
        scheduler.schedule(next_tick, poll_cb);
    };

    // setup things necessary for discovering ASSOCIATE
    auto assoc_backoff_seconds = cfg.backoff_start;
    assoc_cb = [&] {
        recording::RecorderInfo ri;
        populate_recorder_info(ri, cfg, vm_id, start_time, 0);
        auto serialized_size = ri.ByteSize();
        send.ensure_free(serialized_size);
        ri.SerializeToArray(send.buff, send.capacity);
        send.write_end = serialized_size;
        send.read_end = 0;
        curl_easy_setopt(curl.get(), CURLOPT_INFILESIZE, serialized_size);
        recv.write_end = recv.read_end = 0;
        std::string service_endpoint_url = cfg.service_endpoint + std::string("/association");
        logger->info("Calling service-endpoint {} for associate resolution", service_endpoint_url);
        if (do_call(curl, service_endpoint_url.c_str(), "associate-discovery", 0, s_t_associate_rpc, s_c_associate_rpc_failures)) {
            assoc_backoff_seconds = cfg.backoff_start;
            recording::AssignedBackend assigned;
            assigned.ParseFromArray(recv.buff + recv.read_end, recv.write_end - recv.read_end);
            poll_host = assigned.host();
            poll_port = assigned.port();
            poll_url = "http://" + poll_host + ":" + std::to_string(poll_port) + "/poll";
            logger->info("Connecting to associate: {}", poll_url);
            scheduler.schedule(Time::now(), poll_cb);
        } else {
            auto next_attempt = Time::now() + Time::sec(backoff(assoc_backoff_seconds, cfg.backoff_multiplier, cfg.backoff_max));
            scheduler.schedule(next_attempt, assoc_cb);
        }
    };

    // discover associate (it'll setup poll post discovery)
    scheduler.schedule(Time::now(), assoc_cb);

    // work, work work...
    while (keep_running.load(std::memory_order_relaxed) && scheduler.poll());
}

void Controller::with_current_work(std::function<void(Controller::W&, Controller::WSt&, Controller::WRes&, Time::Pt&, Time::Pt&)> proc) {
    std::lock_guard<std::recursive_mutex> current_work_guard(current_work_mtx);
    proc(current_work, current_work_state, current_work_result, work_start, work_end);
}

void Controller::accept_work(Buff& poll_response_buff, const std::string& host, const std::uint32_t port) {
    recording::PollRes res;
    logger->trace("Accept-work host: {} and port: {}", host, port);
    auto parse_successful = res.ParseFromArray(poll_response_buff.buff + poll_response_buff.read_end, poll_response_buff.write_end - poll_response_buff.read_end);
    if (! parse_successful) {
        logger->error("Parse of poll-response failed, ignoring it");
        return;
    }
    if (res.has_assignment()) {
        with_current_work([&](Controller::W& w, Controller::WSt& wst, Controller::WRes& wres, Time::Pt& start_tm, Time::Pt& end_tm) {
                auto new_work = res.mutable_assignment();

                if (wst != recording::WorkResponse::complete) {
                    logger->critical("New work (id: {}, desc: {}, controller: {}/{}) issued while current-work (id: {}, desc: {}) is incomplete (state {})", 
                                     new_work->work_id(), new_work->description(), res.controller_id(), res.controller_version(),
                                     w.work_id(), wst, w.description());
                    return;
                }

                logger->trace("New work (id: {}, desc: {}, controller: {}/{}) is being assigned",
                              new_work->work_id(), new_work->description(), res.controller_id(), res.controller_version());

                w.Swap(new_work);

                const auto delay = w.delay();

                start_tm = end_tm = Time::now();

                if ((delay + w.duration()) > 0) {
                    if (! capable(w)) {
                        logger->critical("Ignoring work {}, as capability doesn't match", w.work_id());
                        wst = recording::WorkResponse::complete;
                        wres = recording::WorkResponse::failure;
                        s_c_work_failure.inc();
                        return;
                    }
                    wst = recording::WorkResponse::pre_start;
                    wres = recording::WorkResponse::unknown;
                    issue_work(host, port, res.controller_id(), res.controller_version());
                } else {
                    wst = recording::WorkResponse::complete;
                    wres = recording::WorkResponse::success;
                }
            });
    } else {
        logger->debug("Controller {}/{} issued no-work", res.controller_id(), res.controller_version());
    }
}

void http_raw_writer_runnable(jvmtiEnv *jvmti_env, JNIEnv *jni_env, void *arg);

typedef std::tuple<BlockingRingBuffer*, metrics::Timer*, metrics::Hist*> ProfileDataReadCtx;

static int ring_to_curl(char *out_buff, size_t size, size_t nitems, void *_ctx) {
    auto& ctx = *static_cast<ProfileDataReadCtx*>(_ctx);
    auto ring = std::get<0>(ctx);
    auto timer_ctx = std::get<1>(ctx)->time_scope();
    auto copied = ring->read(reinterpret_cast<std::uint8_t*>(out_buff), 0, size * nitems);
    logger->debug("Writing {} bytes of recorded data", copied);
    std::get<2>(ctx)->update(copied);
    return copied;
}

static int log_curl_response(char *in_buff, size_t size, size_t nmemb, void *recv_buff) {
    int sz = size * nmemb;
    std::string body(in_buff, sz);
    std::string ctx(static_cast<const char*>(recv_buff));
    logger->info("Curl {} response body: '{}'", ctx, body);
    return sz;
}

class HttpRawProfileWriter : public RawWriter {
private:
    std::string host;
    std::uint32_t port;
    //This ring is a 1 copy approach. But this is needed (and acceptable), because alternatives aren't very lucrative.
    //
    //The problem is, we have atomicity boundry across 3 writes (length, object itself and its checksum, check ProfileWriter)
    //  which means, critical section for write to ring-buffer will have to extend around the 3 writes (one being heavy serialization op).
    //  Ideally, serialization, being heavy operation it is, should happen outside critical section to keep contention low, but then variable-length encoding
    //  of integers (length, checksum) prevents us from accurately predicting the space necessary. We can over-provision and later jump over empty space
    //  in the ring-buffer, but if that is to become a pointer chase, it kinda looses the point.
    //
    //So the solution is to have a 1 copy approach, which has additional-copy overhead, but also has some advantages. Eg. now the extra copy step
    //  can be used for other desirable things like compression.
    BlockingRingBuffer& ring;

    std::function<void()>& cancellation_fn;

    std::uint32_t tx_timeout;

    ThdProcP thd_proc;

    metrics::Timer& s_t_rpc;
    metrics::Ctr& s_c_rpc_failures;
    
    metrics::Timer& s_t_fill_wait;
    metrics::Hist& s_h_req_chunk_sz;

public:
    HttpRawProfileWriter(JavaVM *jvm, jvmtiEnv *jvmti, const std::string& _host, const std::uint32_t _port,
                         BlockingRingBuffer& _ring, std::function<void()>& _cancellation_fn, const std::uint32_t _tx_timeout) :
        RawWriter(), host(_host), port(_port), ring(_ring), cancellation_fn(_cancellation_fn), tx_timeout(_tx_timeout),

        s_t_rpc(get_metrics_registry().new_timer({METRICS_DOMAIN, METRICS_TYPE_RPC, "profile"})),
        s_c_rpc_failures(get_metrics_registry().new_counter({METRICS_DOMAIN, METRICS_TYPE_RPC, "profile", "failures"})),

        s_t_fill_wait(get_metrics_registry().new_timer({METRICS_DOMAIN, METRICS_TYPE_WAIT, "profile", "req_data_feed"})),
        s_h_req_chunk_sz(get_metrics_registry().new_histogram({METRICS_DOMAIN, METRICS_TYPE_SZ, "profile", "chunk"})) {

        ring.reset();
        thd_proc = start_new_thd(jvm, jvmti, "Fk-Prof Profiler Writer Thread", http_raw_writer_runnable, this);
    }
    virtual ~HttpRawProfileWriter() {
        logger->trace("HTTP RawProfileWriter destructor called");
        ring.readonly();
        await_thd_death(thd_proc);
    }

    void write_unbuffered(const std::uint8_t* data, std::uint32_t sz, std::uint32_t offset) {
        ring.write(data, offset, sz);
    }

    void run() {
        std::stringstream ss;
        ss << "http://" << host << ":" << port << "/profile";
        const std::string url = ss.str();
        logger->info("Will now post profile to associate: {}", url);

        Curl curl(curl_easy_init(), curl_easy_cleanup);
        CurlHeader header_list(default_http_req_headers());
        if (curl.get() == nullptr || header_list == nullptr) {
            logger->error("Controller couldn't post profile because cURL init failed");
            return;
        }
        curl_easy_setopt(curl.get(), CURLOPT_HTTPHEADER, header_list.get());
        curl_easy_setopt(curl.get(), CURLOPT_POST, 1L);

        ProfileDataReadCtx rd_ctx {&ring, &s_t_fill_wait, &s_h_req_chunk_sz};

        curl_easy_setopt(curl.get(), CURLOPT_READDATA, &rd_ctx);
        curl_easy_setopt(curl.get(), CURLOPT_READFUNCTION, ring_to_curl);
        curl_easy_setopt(curl.get(), CURLOPT_TIMEOUT, tx_timeout);

        curl_easy_setopt(curl.get(), CURLOPT_WRITEDATA, "PROFILE-RPC");
        curl_easy_setopt(curl.get(), CURLOPT_WRITEFUNCTION, log_curl_response);

        if (do_call(curl, url.c_str(), "send-profile", 0, s_t_rpc, s_c_rpc_failures)) {
            logger->info("Profile posted successfully!");
        } else {
            logger->error("Couldn't post profile, http request failed, cancelling work.");
            cancellation_fn();
        }
    }
};

void http_raw_writer_runnable(jvmtiEnv *jvmti_env, JNIEnv *jni_env, void *arg) {
    logger->trace("HTTP raw-writer thread target entered");
    auto rpw = static_cast<HttpRawProfileWriter*>(arg);
    rpw->run();
}

void populate_recording_header(recording::RecordingHeader& rh, const recording::WorkAssignment& w, std::uint32_t controller_id, std::uint32_t controller_version) {
    rh.set_recorder_version(RECORDER_VERION);
    rh.set_controller_version(controller_version);
    rh.set_controller_id(controller_id);
    recording::WorkAssignment* wa = rh.mutable_work_assignment();
    *wa = w;
}

void Controller::issue_work(const std::string& host, const std::uint32_t port, std::uint32_t controller_id, std::uint32_t controller_version) {
    auto at = Time::now() + Time::sec(current_work.delay());
    scheduler.schedule(at, [&, port, controller_id, controller_version]() {
            with_current_work([&](Controller::W& w, Controller::WSt& wst, Controller::WRes& wres, Time::Pt& start_tm, Time::Pt& end_tm) {
                    auto work_id = w.work_id();
                    cancel_work = [&, work_id]() {
                        raw_writer_ring.readonly(); //because now there is no point in reading/writing anything anyway, this prevents some races:
                        // 1. Where processor wants to write more but raw-writer can't read any more
                        // 2. When user shuts down the JVM while profile is being recorded
                        scheduler.schedule(Time::now(), [&] {
                                wres = recording::WorkResponse::failure;
                                retire_work(work_id);
                            });
                    };

                    if (w.work_size() > 0) {//something actually needs to be done
                        std::uint32_t tx_timeout = cfg.slow_tx_tolerance * w.duration();
                        std::shared_ptr<HttpRawProfileWriter> raw_writer(new HttpRawProfileWriter(jvm, jvmti, host, port, raw_writer_ring, cancel_work, tx_timeout));
                        writer.reset(new ProfileWriter(raw_writer, buff));
                        recording::RecordingHeader rh;
                        populate_recording_header(rh, w, controller_id, controller_version);
                        writer->write_header(rh);

                        for (auto i = 0; i < w.work_size(); i++) {
                            auto work = w.work(i);
                            prep(work);
                        }

                        serializer.reset(new ProfileSerializingWriter(jvmti, *writer.get(), SiteResolver::method_info, SiteResolver::line_no, get_ctx_reg(), sft, tts, cfg.noctx_cov_pct));

                        JNIEnv *env = getJNIEnv(jvm);
                        Processes processes;
                        for (auto i = 0; i < w.work_size(); i++) {
                            auto work = w.work(i);
                            issue(work, processes, env);
                        }

                        processor.reset(new Processor(jvmti, std::move(processes)));
                        processor->start(env);
                    }

                    start_tm = Time::now();
                    auto stop_at = start_tm + Time::sec(w.duration());
                    scheduler.schedule(stop_at, [&, work_id]() {
                            retire_work(work_id);
                        });
                    logger->info("Issuing work-id {} (sz: {}), it is slated for retire in {} seconds", w.work_id(), w.work_size(), w.duration());
                    wst = recording::WorkResponse::running;
                });
        });
}

void Controller::retire_work(const std::uint64_t work_id) {
    with_current_work([&](Controller::W& w, Controller::WSt& wst, Controller::WRes& wres, Time::Pt& start_tm, Time::Pt& end_tm) {
            if (w.work_id() != work_id) {
                logger->warn("Stale work-retire call (target work_id was {}, current work_id is {}), ignoring", work_id, w.work_id());
                return;//TODO: test me!
            }
            if (wst == recording::WorkResponse::complete) {
                logger->info("Work {} has already been retired (result: {}), ignoring stale retire call", work_id, wres);
                return;
            }

            if (w.work_size() > 0) {//something actually was being done
                processor->stop();
                processor.reset();

                logger->info("Will now retire work {}", work_id);

                for (auto i = 0; i < w.work_size(); i++) {
                    auto work = w.work(i);
                    retire(work);
                }

                serializer.reset();
                writer.reset();
            }

            logger->info("Retiring work-id {}, status before retire {}", w.work_id(), wres);
            wst = recording::WorkResponse::complete;
            if ( wres == recording::WorkResponse::unknown) {
                wres = recording::WorkResponse::success;
            }
            end_tm = Time::now();


            s_c_work_retired.inc();
            if (wres == recording::WorkResponse::success) {
                s_c_work_success.inc();
            } else {
                s_c_work_failure.inc();
            }
        });
}

bool has_cpu_sample_work_p(const recording::Work& work) {
    if (work.has_cpu_sample()) return true;
    logger->error("Work of CPU-sampling-type {} doesn't have a definition", work.w_type());
    return false;
}

bool Controller::capable(const W& w) {
    for (auto i = 0; i < w.work_size(); i++) {
        auto work = w.work(i);
        if (! capable(work)) return false;
    }
    return true;
}

bool Controller::capable(const recording::Work& work) {
    auto w_type = work.w_type();
    switch(w_type) {
    case recording::WorkType::cpu_sample_work:
        return has_cpu_sample_work_p(work) && capable(work.cpu_sample());
    default:
        logger->error("Encountered unknown work type {}", w_type);
        return false;
    }
}

void Controller::prep(const recording::Work& work) {
    auto w_type = work.w_type();
    switch(w_type) {
    case recording::WorkType::cpu_sample_work:
        if (has_cpu_sample_work_p(work)) {
            prep(work.cpu_sample());
        }
        return;
    default:
        assert(false);
    }
}

void Controller::issue(const recording::Work& work, Processes& processes, JNIEnv* env) {
    auto w_type = work.w_type();
    switch(w_type) {
    case recording::WorkType::cpu_sample_work:
        if (has_cpu_sample_work_p(work)) {
            issue(work.cpu_sample(), processes, env);
            s_v_work_cpu_sampling.update(1);
        }
        return;
    default:
        assert(false);
    }
}

void Controller::retire(const recording::Work& work) {
    auto w_type = work.w_type();
    switch(w_type) {
    case recording::WorkType::cpu_sample_work:
        if (has_cpu_sample_work_p(work)) {
            retire(work.cpu_sample());
            s_v_work_cpu_sampling.update(0);
        }
        return;
    default:
        assert(false);
    }
}

bool Controller::capable(const recording::CpuSampleWork& csw) {
    bool capable = cfg.allow_sigprof;
    if (! capable) logger->warn("Not capable of cpu-sampling work");
    return capable;
}

void Controller::prep(const recording::CpuSampleWork& csw) {
    sft.cpu_samples = csw.serialization_flush_threshold();
    tts.cpu_samples_max_stack_sz = Profiler::calculate_max_stack_depth(csw.max_frames());
}

class CpuProfileProcess : public Process {
    ReadsafePtr<Profiler> p;
    bool available;

public:
    CpuProfileProcess() : p(GlobalCtx::recording.cpu_profiler) {
        available = p.available();
    }

    virtual ~CpuProfileProcess() {}

    void run() {
        if (available) p->run();
    }

    void stop() {
        if (available) p->stop();
    }
};

void Controller::issue(const recording::CpuSampleWork& csw, Processes& processes, JNIEnv* env) {
    auto freq = csw.frequency();
    logger->info("Starting cpu-sampling at {} Hz and for upto {} frames", freq, tts.cpu_samples_max_stack_sz);
    
    GlobalCtx::recording.cpu_profiler.reset(new Profiler(jvm, jvmti, thread_map, *serializer.get(), tts.cpu_samples_max_stack_sz, freq, get_prob_pct(), cfg.noctx_cov_pct));
    ReadsafePtr<Profiler> p(GlobalCtx::recording.cpu_profiler);
    p->start(env);
    processes.push_back(new CpuProfileProcess());
}

void Controller::retire(const recording::CpuSampleWork& csw) {
    logger->info("Stopping cpu-sampling");
    GlobalCtx::recording.cpu_profiler.reset();
}

namespace GlobalCtx {
    GlobalCtx::Rec recording;
}
