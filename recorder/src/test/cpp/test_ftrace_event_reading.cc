#include <thread>
#include <regex>
#include <iostream>
#include "test.hh"
#include <util.hh>
#include <cstdlib>
#include <sstream>
#include <fstream>
#include <ftrace/events.hh>

void write_str_to_file(const std::string& path, const std::string str) {
    std::ofstream out(path, std::ios_base::out);
    out << str;
}

std::string make_dir(const std::string& path, const std::string& dirname) {
    auto dir_path = path + "/" + dirname;
    auto ret = mkdir(dir_path.c_str(), S_IRWXU | S_IXGRP | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
    if (ret != 0) throw std::runtime_error("Couldn't create dir: " + dir_path);
    return dir_path;
}

void make_mock_ftrace_dir(const std::string& tmp_dir) {
    auto cmd = "cp -ar test_aid/tracefs_lookalike/* " + tmp_dir + "/";
    auto ret = std::system(cmd.c_str());
    if (ret != 0) throw std::runtime_error("Couldn't copy tracefs lookalike to test events dir");
}

class TestEventHandler : public ftrace::EventHandler {
public:

    TestEventHandler(std::vector<std::string>& _events) : events(_events) {}

    virtual ~TestEventHandler() {}

    void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const ftrace::event::CommonFields& cf, const ftrace::event::SyscallEntry& sys_entry) {
        std::stringstream ss;
        head(ss, cpu, timestamp_ns);
        ss << "SYS_Enter(pid: " << cf.common_pid << "): " << sys_entry.nr;
        events.push_back(ss.str());
    }

    void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const ftrace::event::CommonFields& cf, const ftrace::event::SyscallExit& sys_exit) {
        std::stringstream ss;
        head(ss, cpu, timestamp_ns);
        ss << "SYS_Exit(pid: " << cf.common_pid << "): " << sys_exit.nr;
        events.push_back(ss.str());
    }

    void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const ftrace::event::CommonFields& cf, const ftrace::event::SchedSwitch& sched_switch) {
        std::stringstream ss;
        head(ss, cpu, timestamp_ns);
        ss << "Switch(pid: " << cf.common_pid << "): Prev(" << (sched_switch.prev_state == 0) << ") (cmd: " << sched_switch.prev_comm << ", pid: " << sched_switch.prev_pid << ") => Next (cmd: " << sched_switch.next_comm << ", pid: " << sched_switch.next_pid << ")";
        events.push_back(ss.str());
    }

    void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const ftrace::event::CommonFields& cf, const ftrace::event::SchedWakeup& sched_wakeup) {
        std::stringstream ss;
        head(ss, cpu, timestamp_ns);
        ss << "Wakeup(pid: " << cf.common_pid << "): (cmd: " << sched_wakeup.comm << ", pid: " << sched_wakeup.pid << ", target-cpu: " << sched_wakeup.target_cpu << ")";
        events.push_back(ss.str());

    }

private:
    void head(std::stringstream& strm, std::int32_t cpu, std::uint64_t timestamp_ns) {
        auto t_us = timestamp_ns / 1000;
        auto t_s = (t_us / 1000 / 1000);
        t_us -= (t_s * 1000 * 1000);
        strm << '[' << cpu << ']' << "T: " << t_s << "." << t_us << " -> ";
    }
    
    std::vector<std::string>& events;

};

TEST(EventParsing_for_sched_switch_for_a_single_sleepy_process_from_linux_4_5_0) {
    TestEnv env;
    auto& tmp_dir = env.mk_tmp_dir();
    make_mock_ftrace_dir(tmp_dir);

    std::vector<std::string> events;
    TestEventHandler teh(events);
    std::string events_dir = tmp_dir + "/instances/fk-prof-rec/events";
    ftrace::EventReader e_rdr(events_dir, teh);
    ftrace::PageReader p_rdr(events_dir, e_rdr, 4096);
    std::uint8_t buff[4096];

    auto raw_pipe = tmp_dir + "/instances/fk-prof-rec/per_cpu/cpu1/trace_pipe_raw";
    auto fd = open(raw_pipe.c_str(), O_RDONLY);
    assert(fd > 0);
    auto len = read(fd, buff, sizeof(buff));
    CHECK_EQUAL(4096, len);
    auto data_sz = p_rdr.read(1, buff);
    CHECK_EQUAL(92, data_sz);

    len = read(fd, buff, sizeof(buff));
    CHECK_EQUAL(4096, len);
    data_sz = p_rdr.read(1, buff);
    CHECK_EQUAL(84, data_sz);
    close(fd);

    CHECK_EQUAL(2, events.size());
    CHECK_EQUAL("[1]T: 38939.74761 -> Switch(pid: 0): Prev(1) (cmd: swapper/1, pid: 0) => Next (cmd: sleepy_head, pid: 8411)", events[0]);
    CHECK_EQUAL("[1]T: 38939.74783 -> Switch(pid: 8411): Prev(0) (cmd: sleepy_head, pid: 8411) => Next (cmd: swapper/1, pid: 0)", events[1]);
}

TEST(EventParsing_for_2_process_from_linux_4_5_0) {
    TestEnv env;
    auto& tmp_dir = env.mk_tmp_dir();
    make_mock_ftrace_dir(tmp_dir);

    std::vector<std::string> events;
    TestEventHandler teh(events);
    std::string events_dir = tmp_dir + "/instances/fk-prof-rec/events";
    ftrace::EventReader e_rdr(events_dir, teh);
    ftrace::PageReader p_rdr(events_dir, e_rdr, 4096);
    std::uint8_t buff[4096];

    auto raw_pipe = tmp_dir + "/instances/fk-prof-rec/per_cpu/cpu2/trace_pipe_raw";
    auto fd = open(raw_pipe.c_str(), O_RDONLY);
    assert(fd > 0);
    auto len = read(fd, buff, sizeof(buff));
    CHECK_EQUAL(4096, len);
    auto data_sz = p_rdr.read(1, buff);
    CHECK_EQUAL(440, data_sz);

    len = read(fd, buff, sizeof(buff));
    CHECK_EQUAL(4096, len);
    data_sz = p_rdr.read(1, buff);
    CHECK_EQUAL(232, data_sz);

    len = read(fd, buff, sizeof(buff));
    CHECK_EQUAL(4096, len);
    data_sz = p_rdr.read(1, buff);
    CHECK_EQUAL(224, data_sz);

    len = read(fd, buff, sizeof(buff));
    CHECK_EQUAL(0, len);
    close(fd);

    CHECK_EQUAL(16, events.size());

    CHECK_EQUAL("[1]T: 50165.401873 -> Wakeup(pid: 0): (cmd: weepy_head, pid: 8367, target-cpu: 2)", events[0]);
    CHECK_EQUAL("[1]T: 50165.401887 -> Switch(pid: 0): Prev(1) (cmd: swapper/2, pid: 0) => Next (cmd: weepy_head, pid: 8367)", events[1]);
    CHECK_EQUAL("[1]T: 50165.401889 -> SYS_Exit(pid: 8367): 35", events[2]);
    CHECK_EQUAL("[1]T: 50165.401895 -> SYS_Enter(pid: 8367): 1", events[3]);
    CHECK_EQUAL("[1]T: 50165.401900 -> Wakeup(pid: 8367): (cmd: kworker/u16:2, pid: 3302, target-cpu: 0)", events[4]);
    CHECK_EQUAL("[1]T: 50165.401901 -> SYS_Exit(pid: 8367): 1", events[5]);
    CHECK_EQUAL("[1]T: 50165.401902 -> SYS_Enter(pid: 8367): 35", events[6]);
    CHECK_EQUAL("[1]T: 50165.401903 -> Switch(pid: 8367): Prev(0) (cmd: weepy_head, pid: 8367) => Next (cmd: swapper/2, pid: 0)", events[7]);
    CHECK_EQUAL("[1]T: 50175.401649 -> Wakeup(pid: 0): (cmd: weepy_head, pid: 8367, target-cpu: 2)", events[8]);
    CHECK_EQUAL("[1]T: 50175.401656 -> Switch(pid: 0): Prev(1) (cmd: swapper/2, pid: 0) => Next (cmd: weepy_head, pid: 8367)", events[9]);
    CHECK_EQUAL("[1]T: 50175.401657 -> SYS_Exit(pid: 8367): 35", events[10]);
    CHECK_EQUAL("[1]T: 50175.401673 -> SYS_Enter(pid: 8367): 1", events[11]);
    CHECK_EQUAL("[1]T: 50175.401678 -> Wakeup(pid: 8367): (cmd: kworker/u16:2, pid: 3302, target-cpu: 3)", events[12]);
    CHECK_EQUAL("[1]T: 50175.401679 -> SYS_Exit(pid: 8367): 1", events[13]);
    CHECK_EQUAL("[1]T: 50175.401680 -> SYS_Enter(pid: 8367): 35", events[14]);
    CHECK_EQUAL("[1]T: 50175.401681 -> Switch(pid: 8367): Prev(0) (cmd: weepy_head, pid: 8367) => Next (cmd: swapper/2, pid: 0)", events[15]);

}

