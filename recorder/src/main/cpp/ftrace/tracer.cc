#include "ftrace/tracer.hh"
#include <cassert>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>
#include <fstream>
#include "logging.hh"
#include "util.hh"

#define TRACING_ON "/tracing_on"
#define TRACE_OPTIONS "/trace_options"
#define INSTANCES_DIR "/instances"
#define INSTANCE "/fk-prof-rec"
#define ENABLE_FILE "/enable"
#define CPU_DIR_PREFIX "/per_cpu/cpu"
#define PER_CPU_RAW_TRACE_PIPE "/trace_pipe_raw"
#define PER_CPU_STATS "/stats"


void throw_file_not_found(const std::string& path, const std::string& type = "file") {
    logger->error("Couldn't find {}: {}, perhaps this version of Linux is not supported?", type, path);
    throw std::runtime_error("Couldn't find " + type + ": " + path);
}

static int open_file(const std::string& instance_path, const std::string& subpath, int flags) {
    auto path = instance_path + subpath;
    if (! Util::file_exists(path.c_str())) {
        throw_file_not_found(path);
    }
    return open(path.c_str(), flags);
}

std::uint16_t cpus_present() {
    std::fstream in{"/sys/devices/system/cpu/present", std::ios_base::in};
    std::string content;
    in >> content;
    auto idx = content.find('-');
    return Util::stoun<std::uint16_t>(content.substr(idx + 1));
}

static void populate_data_links(const std::string& instance_path, std::list<ftrace::Tracer::DataLink>& dls, std::function<void(const ftrace::Tracer::DataLink&)>& data_link_listener) {
    std::uint16_t nr_cpu = cpus_present();
    for (std::uint16_t c = 0; c < nr_cpu; c++) {
        auto cpu_dir = CPU_DIR_PREFIX + std::to_string(c);
        auto ring_fd = open_file(instance_path, cpu_dir + PER_CPU_RAW_TRACE_PIPE, O_RDONLY);
        auto stats_fd = open_file(instance_path, cpu_dir + PER_CPU_STATS, O_RDONLY);
        dls.push_back({ring_fd, stats_fd, c});
        data_link_listener(dls.back());
    }
}

void append(int fd, const std::string buff) {
    auto wr_sz = write(fd, buff.c_str(), buff.length());
    assert(wr_sz == buff.length());
}

#define METRIC_TYPE "tracer"

ftrace::Tracer::Tracer(const std::string& tracing_dir, Listener& listener, std::function<void(const ftrace::Tracer::DataLink&)> data_link_listener) :
    evt_hdlr(listener, tracees), pg_sz(getpagesize()), pg_buff(new std::uint8_t[pg_sz]),

    s_c_read_failed(get_metrics_registry().new_counter({METRICS_DOMAIN_TRACE, METRIC_TYPE, "pipe_read", "failed"})),
    s_m_read_bytes(get_metrics_registry().new_meter({METRICS_DOMAIN_TRACE, METRIC_TYPE, "pipe_read", "bytes"}, "rate")) {

    auto instances_dir = tracing_dir + INSTANCES_DIR;
    if (! Util::dir_exists(instances_dir.c_str())) {
        throw_file_not_found(instances_dir, "dir");
    }
    instance_path = tracing_dir + INSTANCES_DIR + INSTANCE;
    if (! Util::dir_exists(instance_path.c_str())) {
        auto ret = mkdir(instance_path.c_str(), S_IRWXU | S_IXGRP | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
        assert(ret == 0);
    }
    ctrl_fds.tracing_on = open_file(instance_path, TRACING_ON, O_WRONLY | O_TRUNC);
    ctrl_fds.trace_options = open_file(instance_path, TRACE_OPTIONS,  O_WRONLY | O_APPEND);
    ctrl_fds.sched_switch_enable = open_file(instance_path, EVENTS_DIR SCHED_SWITCH_DIR ENABLE_FILE,  O_WRONLY | O_APPEND);
    ctrl_fds.sched_wakeup_enable = open_file(instance_path, EVENTS_DIR SCHED_WAKEUP_DIR ENABLE_FILE,  O_WRONLY | O_APPEND);
    ctrl_fds.syscall_enter_enable = open_file(instance_path, EVENTS_DIR SYSCALL_ENTER_DIR ENABLE_FILE,  O_WRONLY | O_APPEND);
    ctrl_fds.syscall_exit_enable = open_file(instance_path, EVENTS_DIR SYSCALL_EXIT_DIR ENABLE_FILE,  O_WRONLY | O_APPEND);
    
    populate_data_links(instance_path, dls, data_link_listener);

    append(ctrl_fds.trace_options, "bin");
    append(ctrl_fds.trace_options, "nooverwrite");

    const auto events_dir = instance_path + EVENTS_DIR;
    evt_reader.reset(new EventReader(events_dir, evt_hdlr));
    pg_reader.reset(new PageReader(events_dir, *evt_reader.get(), pg_sz));
}

ftrace::Tracer::~Tracer() {
    stop();
    pg_reader.reset();
    evt_reader.reset();
    for (const auto& dl : dls) {
        close(dl.pipe_fd);
        close(dl.stats_fd);
    }
    //close(set_event_pid); //not enabled yet
    close(ctrl_fds.syscall_exit_enable);
    close(ctrl_fds.syscall_enter_enable);
    close(ctrl_fds.sched_wakeup_enable);
    close(ctrl_fds.sched_switch_enable);
    close(ctrl_fds.trace_options);
    close(ctrl_fds.tracing_on);
}

void ftrace::Tracer::trace_on(pid_t pid, void* ctx) {
    bool is_first_pid = tracees.empty();
    tracees[pid] = ctx;
    if (is_first_pid) start();
}

void ftrace::Tracer::trace_off(pid_t pid) {
    tracees.erase(pid);
    evt_hdlr.untrack_tid(pid);
    bool was_last_pid = tracees.empty();
    if (was_last_pid) stop();
}

#define ON "1"
#define OFF "0"

void ftrace::Tracer::start() {
    append(ctrl_fds.syscall_exit_enable,  ON);
    append(ctrl_fds.syscall_enter_enable, ON);
    append(ctrl_fds.sched_wakeup_enable,  ON);
    append(ctrl_fds.sched_switch_enable,  ON);
    append(ctrl_fds.tracing_on,           ON);
}

void ftrace::Tracer::stop() {
    append(ctrl_fds.syscall_exit_enable,  OFF);
    append(ctrl_fds.syscall_enter_enable, OFF);
    append(ctrl_fds.sched_wakeup_enable,  OFF);
    append(ctrl_fds.sched_switch_enable,  OFF);
    append(ctrl_fds.tracing_on,           OFF);
}

void ftrace::Tracer::process(const DataLink& dl) {
    auto buff = pg_buff.get();
    while (true) {
        auto rd_sz = read(dl.pipe_fd, buff, pg_sz);
        if (rd_sz > 0) {
            auto read = pg_reader->read(dl.cpu, buff);
            s_m_read_bytes.mark(read);
            if (read < rd_sz) {
                break;
            }
        } else if (rd_sz < 0) {
            if ((errno != EAGAIN) && (errno != EWOULDBLOCK)) {
                s_c_read_failed.inc();
                auto msg =  error_message("read of raw-trace-pipe failed", errno);
                logger->warn("For cpu {}, {}", dl.cpu, msg);
            }
            break;
        } else {
            s_c_read_failed.inc();
            logger->error("For cpu {}, raw-trace-pipe read returned 0", dl.cpu);
            break;
        }
    }
}

ftrace::Tracer::SwitchTrackingEventHandler::SwitchTrackingEventHandler(ftrace::Tracer::Listener& _listener, const ftrace::Tracer::Tracees& _tracees) : listener(_listener), tracees(_tracees) {}

ftrace::Tracer::SwitchTrackingEventHandler::~SwitchTrackingEventHandler() {}

void ftrace::Tracer::SwitchTrackingEventHandler::handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SyscallEntry& sys_entry) {
    auto tid = cf.common_pid;
    current_syscall[tid] = sys_entry.nr;
}

void ftrace::Tracer::SwitchTrackingEventHandler::handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SyscallExit& sys_exit) {
    auto tid = cf.common_pid;
    current_syscall[tid] = -1;
}

void ftrace::Tracer::SwitchTrackingEventHandler::handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SchedSwitch& sched_switch) {
    auto prev_pid = sched_switch.prev_pid;
    auto prev_it = tracees.find(prev_pid);
    auto prev_traced = (prev_it != std::end(tracees));
    
    auto next_pid = sched_switch.next_pid;
    auto next_it = tracees.find(next_pid);
    auto next_traced = (next_it != std::end(tracees));
    
    if (! (prev_traced || next_traced)) return;
    auto prev_syscall_it = current_syscall.find(prev_pid);
    std::int64_t syscall_nr = -1;
    if (prev_syscall_it != std::end(current_syscall)) {
        syscall_nr = prev_syscall_it->second;
    }
    auto runnable = (sched_switch.prev_state == 0); // check <linux>/include/linux/sched.h task state bitmask (TASK_RUNNING)
    ftrace::v_curr::payload::SchedSwitch sched_switch_msg {timestamp_ns, prev_pid, next_pid, syscall_nr, cpu, ! runnable};

    if (next_traced) next_traced = ! (prev_traced && (prev_it->second == next_it->second));
    
    if (prev_traced)
        listener.unicast(prev_pid, prev_it->second, ftrace::v_curr::PktType::sched_switch, reinterpret_cast<std::uint8_t*>(&sched_switch_msg), sizeof(sched_switch_msg));

    if (next_traced)
        listener.unicast(next_pid, next_it->second, ftrace::v_curr::PktType::sched_switch, reinterpret_cast<std::uint8_t*>(&sched_switch_msg), sizeof(sched_switch_msg));
}

void ftrace::Tracer::SwitchTrackingEventHandler::handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SchedWakeup& sched_wakeup) {
    auto prev_pid = sched_wakeup.pid;
    auto prev_it = tracees.find(prev_pid);
    auto prev_traced = prev_it != std::end(tracees);
    if (! prev_traced) return;
    ftrace::v_curr::payload::SchedWakeup sched_wakeup_msg {timestamp_ns, sched_wakeup.target_cpu, prev_pid, cpu};
    listener.unicast(prev_pid, prev_it->second, ftrace::v_curr::PktType::sched_wakeup, reinterpret_cast<std::uint8_t*>(&sched_wakeup_msg), sizeof(sched_wakeup_msg));
}

void ftrace::Tracer::SwitchTrackingEventHandler::untrack_tid(pid_t tid) {
    current_syscall.erase(tid);
}
