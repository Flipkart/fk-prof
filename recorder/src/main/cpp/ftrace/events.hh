#ifndef FTRACE_EVENTS_H
#define FTRACE_EVENTS_H

#include <cstdint>
#include <string>
#include <memory>
#include <regex>

#define EVENTS_DIR "/events"
#define SCHED_SWITCH_DIR "/sched/sched_switch"
#define SCHED_WAKEUP_DIR "/sched/sched_wakeup"
#define SYSCALL_ENTER_DIR "/raw_syscalls/sys_enter"
#define SYSCALL_EXIT_DIR "/raw_syscalls/sys_exit"

namespace ftrace {
    namespace event {
        struct __attribute__((packed)) SyscallEntry {
            std::int64_t nr;
            std::uint64_t args[6];
        };

        struct __attribute__((packed)) SyscallExit {
            std::int64_t nr;
            std::int64_t ret;
        };

        struct __attribute__((packed)) CommonFields {
            std::uint16_t common_type;
            std::uint8_t common_flags;
            std::uint8_t common_preempt_count;
            std::int32_t common_pid;
        };

        struct __attribute__((packed)) SchedSwitch {
            char prev_comm[16];
            std::int32_t prev_pid;
            std::int32_t prev_prio;
            std::int64_t prev_state;
            char next_comm[16];
            std::int32_t next_pid;
            std::int32_t next_prio;
        };

        struct __attribute__((packed)) SchedWakeup {
            char comm[16];
            std::int32_t pid;
            std::int32_t prio;
            std::int32_t success;
            std::int32_t target_cpu;
        };
    }

    class EventHandler {
    public:
        virtual ~EventHandler() {};

        virtual void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SyscallEntry& sys_entry) = 0;
        virtual void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SyscallExit& sys_exit) = 0;
        virtual void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SchedSwitch& sched_switch) = 0;
        virtual void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SchedWakeup& sched_wakeup) = 0;
    };

    class CommonHeaderReader {
    public:
        virtual ~CommonHeaderReader() {};

        virtual std::size_t read(const std::uint8_t* buff, std::size_t remaining, event::CommonFields& common_fields) = 0;

        virtual std::size_t repr_length() = 0;
    };

    class SyscallEntryReader {
    public:
        virtual ~SyscallEntryReader() {};

        virtual std::size_t read(const std::uint8_t* buff, std::size_t remaining, event::SyscallEntry& sys_entry) = 0;

        virtual std::size_t repr_length() = 0;
    };

    class SyscallExitReader {
    public:
        virtual ~SyscallExitReader() {};

        virtual std::size_t read(const std::uint8_t* buff, std::size_t remaining, event::SyscallExit& sys_exit) = 0;

        virtual std::size_t repr_length() = 0;
    };

    class SchedSwitchReader {
    public:
        virtual ~SchedSwitchReader() {};

        virtual std::size_t read(const std::uint8_t* buff, std::size_t remaining, event::SchedSwitch& sched_switch) = 0;

        virtual std::size_t repr_length() = 0;
    };

    class SchedWakeupReader {
    public:
        virtual ~SchedWakeupReader() {};

        virtual std::size_t read(const std::uint8_t* buff, std::size_t remaining, event::SchedWakeup& sched_wakeup) = 0;

        virtual std::size_t repr_length() = 0;
    };

    class EventReader {
    public:
        EventReader(const std::string& _events_dir, EventHandler& _handler);

        virtual ~EventReader();

        std::size_t read(std::int32_t cpu, std::uint64_t timestamp_ns, const std::uint8_t* buff, std::size_t remaining) const;

    private:
        std::string::size_type create_sched_switch_and_common_fields_reader(const std::string& events_dir, std::regex& start_marker, std::regex& end_marker);

        void create_sched_wakeup_reader(const std::string& events_dir, std::regex& start_marker, std::regex& end_marker, std::string::size_type specific_fields_offset);

        void create_syscall_entry_reader(const std::string& events_dir, std::regex& start_marker, std::regex& end_marker, std::string::size_type specific_fields_offset);

        void create_syscall_exit_reader(const std::string& events_dir, std::regex& start_marker, std::regex& end_marker, std::string::size_type specific_fields_offset);

        std::size_t read_payload(const std::uint8_t* buff, std::size_t remaining, std::uint64_t timestamp_ns, std::int32_t cpu) const;

        EventHandler& handler;

        std::regex numeric;

        std::unique_ptr<CommonHeaderReader> common_header_rdr;

        std::uint16_t sys_entry_id;
        std::unique_ptr<SyscallEntryReader> sys_entry_rdr;

        std::uint16_t sys_exit_id;
        std::unique_ptr<SyscallExitReader> sys_exit_rdr;

        std::uint16_t sched_switch_id;
        std::unique_ptr<SchedSwitchReader> sched_switch_rdr;

        std::uint16_t sched_wakeup_id;
        std::unique_ptr<SchedWakeupReader> sched_wakeup_rdr;

    };

    class PageReader {
    public:
        PageReader(const std::string& events_dir, const EventReader& _e_rdr, std::size_t pg_sz);

        PageReader();

        virtual ~PageReader();

        std::size_t read(std::int32_t cpu, const std::uint8_t* page);

    private:
        const EventReader& e_rdr;

        std::size_t pg_sz;

    };
};

#endif
