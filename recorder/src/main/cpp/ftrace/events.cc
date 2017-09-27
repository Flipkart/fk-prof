#include "events.hh"
#include "logging.hh"
#include "util.hh"
#include <regex>

/**
   There is no documentation or help for the magic that happens here, but who needs documentation when we have source-tree?

   Majority of this is fairly old and has been largely untouched since 2012, I was reffering to 4.5.0 while writing this.

   For page-parsing, check header format in <debugfs>/tracing or <tracefs> files events/header_event and events/header_page

   But header_event has an un-intuitive format that has no documentation, so please refer to <linux>/kernel/trace/ring_buffer.c and <linux>/include/linux/ring_buffer.h

   Check out enum ring_buffer_type and rb_event_length, rb_event_ts_length, rb_buffer_event_length etc to understand how it is used in various cases in ring_buffer.c.

   Happy hacking!

   -jj
*/

#define MARK_CONSUMED(len)                      \
    {                                           \
        {                                       \
            auto l = len;                       \
            buff += l;                          \
            remaining -= l;                     \
        }                                       \
    }

#define CONSUMED (buff - buff_start)

template <typename Event> std::size_t read_copy(const std::uint8_t* buff, std::size_t remaining, Event& event) {
    auto buff_start = buff;
    auto sz = sizeof(Event);
    assert(remaining >= sz);
    event = *reinterpret_cast<const Event*>(buff);
    MARK_CONSUMED(sz);
    return CONSUMED;
}

//TODO: use macros to DRY this stuff up -jj

class CommonHeaderReaderJessie : public ftrace::CommonHeaderReader {
public:
    ~CommonHeaderReaderJessie() {}

    std::size_t read(const std::uint8_t* buff, std::size_t remaining, ftrace::event::CommonFields& common_fields) {
        return read_copy<ftrace::event::CommonFields>(buff, remaining, common_fields);
    }

    std::size_t repr_length() {
        return sizeof(ftrace::event::CommonFields);
    }
};

class SchedSwitchReaderJessie : public ftrace::SchedSwitchReader {
public:
    ~SchedSwitchReaderJessie() {}

    std::size_t read(const std::uint8_t* buff, std::size_t remaining, ftrace::event::SchedSwitch& fields) {
        return read_copy<ftrace::event::SchedSwitch>(buff, remaining, fields);
    }

    std::size_t repr_length() {
        return sizeof(ftrace::event::SchedSwitch);
    }
};

class SchedWakeupReaderJessie : public ftrace::SchedWakeupReader {
public:
    ~SchedWakeupReaderJessie() {}

    std::size_t read(const std::uint8_t* buff, std::size_t remaining, ftrace::event::SchedWakeup& fields) {
        return read_copy<ftrace::event::SchedWakeup>(buff, remaining, fields);
    }

    std::size_t repr_length() {
        return sizeof(ftrace::event::SchedWakeup);
    }
};

class SyscallEntryReaderJessie : public ftrace::SyscallEntryReader {
public:
    ~SyscallEntryReaderJessie() {}

    std::size_t read(const std::uint8_t* buff, std::size_t remaining, ftrace::event::SyscallEntry& fields) {
        return read_copy<ftrace::event::SyscallEntry>(buff, remaining, fields);
    }

    std::size_t repr_length() {
        return sizeof(ftrace::event::SyscallEntry);
    }
};

class SyscallExitReaderJessie : public ftrace::SyscallExitReader {
    ~SyscallExitReaderJessie() {}

    std::size_t read(const std::uint8_t* buff, std::size_t remaining, ftrace::event::SyscallExit& fields) {
        return read_copy<ftrace::event::SyscallExit>(buff, remaining, fields);
    }

    std::size_t repr_length() {
        return sizeof(ftrace::event::SyscallExit);
    }
};

#define EXPECTED_PAGE_HEADER                                   \
    "\tfield: u64 timestamp;\toffset:0;\tsize:8;\tsigned:0;\n"  \
    "\tfield: local_t commit;\toffset:8;\tsize:8;\tsigned:1;\n" \
    "\tfield: int overwrite;\toffset:8;\tsize:1;\tsigned:1;\n"  \
    "\tfield: char data;\toffset:16;\tsize:4080;\tsigned:1;\n"

#define EXPECTED_HEADER_EVENT                   \
    "# compressed entry header\n"               \
    "\ttype_len    :    5 bits\n"               \
    "\ttime_delta  :   27 bits\n"               \
    "\tarray       :   32 bits\n"               \
    "\n"                                        \
    "\tpadding     : type == 29\n"              \
    "\ttime_extend : type == 30\n"              \
    "\tdata max type_len  == 28\n"

#define COMMON_FIELDS_FORMAT_JESSIE                                     \
    "\tfield:unsigned short common_type;\toffset:0;\tsize:2;\tsigned:0;\n" \
    "\tfield:unsigned char common_flags;\toffset:2;\tsize:1;\tsigned:0;\n" \
    "\tfield:unsigned char common_preempt_count;\toffset:3;\tsize:1;\tsigned:0;\n" \
    "\tfield:int common_pid;\toffset:4;\tsize:4;\tsigned:1;"

#define SYSCALL_ENTRY_FORMAT_JESSIE                           \
    "\tfield:long id;\toffset:8;\tsize:8;\tsigned:1;\n"       \
    "\tfield:unsigned long args[6];\toffset:16;\tsize:48;\tsigned:0;\n\n"

#define SYSCALL_EXIT_FORMAT_JESSIE                          \
    "\tfield:long id;\toffset:8;\tsize:8;\tsigned:1;\n"     \
    "\tfield:long ret;\toffset:16;\tsize:8;\tsigned:1;\n\n"

#define SCHED_SWITCH_FORMAT_JESSIE                                  \
    "\tfield:char prev_comm[16];\toffset:8;\tsize:16;\tsigned:1;\n" \
    "\tfield:pid_t prev_pid;\toffset:24;\tsize:4;\tsigned:1;\n"     \
    "\tfield:int prev_prio;\toffset:28;\tsize:4;\tsigned:1;\n"      \
    "\tfield:long prev_state;\toffset:32;\tsize:8;\tsigned:1;\n"     \
    "\tfield:char next_comm[16];\toffset:40;\tsize:16;\tsigned:1;\n" \
    "\tfield:pid_t next_pid;\toffset:56;\tsize:4;\tsigned:1;\n"      \
    "\tfield:int next_prio;\toffset:60;\tsize:4;\tsigned:1;\n\n"

#define SCHED_WAKEUP_FORMAT_JESSIE                                  \
    "\tfield:char comm[16];\toffset:8;\tsize:16;\tsigned:1;\n"      \
    "\tfield:pid_t pid;\toffset:24;\tsize:4;\tsigned:1;\n"          \
    "\tfield:int prio;\toffset:28;\tsize:4;\tsigned:1;\n"           \
    "\tfield:int success;\toffset:32;\tsize:4;\tsigned:1;\n"        \
    "\tfield:int target_cpu;\toffset:36;\tsize:4;\tsigned:1;\n\n"

ftrace::EventReader::EventReader(const std::string& events_dir, EventHandler& _handler) : handler(_handler), numeric("^[0-9]+.*") {
    //TODO: assert event header prefix content matchs
    auto header_event_path = events_dir + "/header_event";
    auto header_event = Util::content(header_event_path, nullptr, nullptr);
    assert(header_event == EXPECTED_HEADER_EVENT);

    std::regex bin_fmt_start_marker("^format:$");
    std::regex text_fmt_start_marker("^print fmt: .+");

    auto specific_fields_offset = create_sched_switch_and_common_fields_reader(events_dir, bin_fmt_start_marker, text_fmt_start_marker);
    create_sched_wakeup_reader(events_dir, bin_fmt_start_marker, text_fmt_start_marker, specific_fields_offset);
    create_syscall_entry_reader(events_dir, bin_fmt_start_marker, text_fmt_start_marker, specific_fields_offset);
    create_syscall_exit_reader(events_dir, bin_fmt_start_marker, text_fmt_start_marker, specific_fields_offset);
}

std::runtime_error get_version_unsupported_error(const std::string& identity, const std::string& format) {
    auto msg = "This kernel version doesn't seem supported. Discovered " + identity + " had an unknown format '" + format + "'";
    logger->error(msg);
    return std::runtime_error(msg);
}

std::string::size_type ftrace::EventReader::create_sched_switch_and_common_fields_reader(const std::string& events_dir, std::regex& bin_fmt_start_marker, std::regex& text_fmt_start_marker) {
    auto sched_switch_event_dir = events_dir + SCHED_SWITCH_DIR;
    auto sched_switch_format_path = sched_switch_event_dir + "/format";
    auto sched_switch_all_format = Util::content(sched_switch_format_path, &bin_fmt_start_marker, &text_fmt_start_marker);
    auto common_fields_end = sched_switch_all_format.find("\n\n");
    assert(common_fields_end != std::string::npos);

    auto common_fields_format = sched_switch_all_format.substr(0, common_fields_end);
    if (common_fields_format == COMMON_FIELDS_FORMAT_JESSIE) {
        common_header_rdr.reset(new CommonHeaderReaderJessie());
    } else {
        throw get_version_unsupported_error("common_fields", common_fields_format);
    }

    auto specific_fields_offset = common_fields_end + 2;

    auto sched_switch_format = sched_switch_all_format.substr(specific_fields_offset);
    if (sched_switch_format == SCHED_SWITCH_FORMAT_JESSIE) {
        sched_switch_rdr.reset(new SchedSwitchReaderJessie());
    } else {
        throw get_version_unsupported_error("sched_switch", sched_switch_format);
    }
    auto sched_switch_id_path = sched_switch_event_dir + "/id";
    auto sched_switch_id_str = Util::first_content_line_matching(sched_switch_id_path, numeric);
    sched_switch_id = Util::stoun<std::uint16_t>(sched_switch_id_str);
    return specific_fields_offset;
}

//TODO: use macros to dry this stuff up -jj

void ftrace::EventReader::create_sched_wakeup_reader(const std::string& events_dir, std::regex& bin_fmt_start_marker, std::regex& text_fmt_start_marker, std::string::size_type specific_fields_offset) {
    auto sched_wakeup_event_dir = events_dir + SCHED_WAKEUP_DIR;
    auto sched_wakeup_format_path = sched_wakeup_event_dir + "/format";
    auto sched_wakeup_all_format = Util::content(sched_wakeup_format_path, &bin_fmt_start_marker, &text_fmt_start_marker);

    auto sched_wakeup_format = sched_wakeup_all_format.substr(specific_fields_offset);
    if (sched_wakeup_format == SCHED_WAKEUP_FORMAT_JESSIE) {
        sched_wakeup_rdr.reset(new SchedWakeupReaderJessie());
    } else {
        throw get_version_unsupported_error("sched_wakeup", sched_wakeup_format);
    }
    auto sched_wakeup_id_path = sched_wakeup_event_dir + "/id";
    auto sched_wakeup_id_str = Util::first_content_line_matching(sched_wakeup_id_path, numeric);
    sched_wakeup_id = Util::stoun<std::uint16_t>(sched_wakeup_id_str);
}

void ftrace::EventReader::create_syscall_entry_reader(const std::string& events_dir, std::regex& bin_fmt_start_marker, std::regex& text_fmt_start_marker, std::string::size_type specific_fields_offset) {
    auto sys_entry_event_dir = events_dir + SYSCALL_ENTER_DIR;
    auto sys_entry_format_path = sys_entry_event_dir + "/format";
    auto sys_entry_all_format = Util::content(sys_entry_format_path, &bin_fmt_start_marker, &text_fmt_start_marker);

    auto sys_entry_format = sys_entry_all_format.substr(specific_fields_offset);
    if (sys_entry_format == SYSCALL_ENTRY_FORMAT_JESSIE) {
        sys_entry_rdr.reset(new SyscallEntryReaderJessie());
    } else {
        throw get_version_unsupported_error("syscall_entry", sys_entry_format);
    }
    auto sys_entry_id_path = sys_entry_event_dir + "/id";
    auto sys_entry_id_str = Util::first_content_line_matching(sys_entry_id_path, numeric);
    sys_entry_id = Util::stoun<std::uint16_t>(sys_entry_id_str);
}

void ftrace::EventReader::create_syscall_exit_reader(const std::string& events_dir, std::regex& bin_fmt_start_marker, std::regex& text_fmt_start_marker, std::string::size_type specific_fields_offset) {
    auto sys_exit_event_dir = events_dir + SYSCALL_EXIT_DIR;
    auto sys_exit_format_path = sys_exit_event_dir + "/format";
    auto sys_exit_all_format = Util::content(sys_exit_format_path, &bin_fmt_start_marker, &text_fmt_start_marker);

    auto sys_exit_format = sys_exit_all_format.substr(specific_fields_offset);
    if (sys_exit_format == SYSCALL_EXIT_FORMAT_JESSIE) {
        sys_exit_rdr.reset(new SyscallExitReaderJessie());
    } else {
        throw get_version_unsupported_error("syscall_exit", sys_exit_format);
    }
    auto sys_exit_id_path = sys_exit_event_dir + "/id";
    auto sys_exit_id_str = Util::first_content_line_matching(sys_exit_id_path, numeric);
    sys_exit_id = Util::stoun<std::uint16_t>(sys_exit_id_str);
}

ftrace::EventReader::~EventReader() {}

std::size_t r_u32(const std::uint8_t* buff, std::size_t remaining, std::uint32_t& val) {
    auto sz = sizeof(std::uint32_t);
    assert(remaining >= sz);
    val = *reinterpret_cast<const std::uint32_t*>(buff);
    return sz;
}

std::size_t r_i64(const std::uint8_t* buff, std::size_t remaining, std::int64_t& val) {
    auto sz = sizeof(std::int64_t);
    assert(remaining >= sz);
    val = *reinterpret_cast<const std::int64_t*>(buff);
    return sz;
}

struct __attribute__((packed)) EvtHdrPrefix {
    std::uint32_t type_len:5, time_delta:27;
};

#define RINGBUF_TYPE_DATA_TYPE_LEN_MAX 28
#define RINGBUF_TYPE_PADDING 29
#define RINGBUF_TYPE_TIME_EXTEND 30

std::size_t ftrace::EventReader::read(std::int32_t cpu, std::uint64_t timestamp_ns, const std::uint8_t* buff, std::size_t remaining) const {
    auto buff_start = buff;
    SPDLOG_TRACE(logger, "Data in page: {}, start: {}, end: {}", pg_hdr.data_len, static_cast<void*>(buff_start), static_cast<void*>(buff_start + remaining));
    while (remaining > 0) {
        SPDLOG_TRACE(logger, "Curr Pos: '{}'", static_cast<void*>(buff));
        auto fhp = reinterpret_cast<const EvtHdrPrefix*>(buff);
        MARK_CONSUMED(sizeof(EvtHdrPrefix));
        SPDLOG_TRACE(logger, "Type-len: {}", fhp->type_len);
        if (fhp->type_len == 0) {
            std::uint32_t arr_0;
            MARK_CONSUMED(r_u32(buff, remaining, arr_0));
            timestamp_ns += fhp->time_delta;
            arr_0 -= sizeof(arr_0);
            assert(remaining >= arr_0);
            MARK_CONSUMED(read_payload(buff, arr_0, timestamp_ns, cpu));
        } else if (fhp->type_len <= RINGBUF_TYPE_DATA_TYPE_LEN_MAX) {
            std::uint32_t len = fhp->type_len;
            len <<= 2;
            timestamp_ns += fhp->time_delta;
            assert(remaining >= len);
            MARK_CONSUMED(read_payload(buff, len, timestamp_ns, cpu));
        } else if (fhp->type_len == RINGBUF_TYPE_PADDING) {
            if (fhp->time_delta == 0) {
                SPDLOG_TRACE(logger, "Padding tail-record");
                return CONSUMED;
            }
            std::uint32_t arr_0;
            MARK_CONSUMED(r_u32(buff, remaining, arr_0));
            arr_0 -= sizeof(arr_0);
            assert(remaining >= arr_0);
            MARK_CONSUMED(arr_0);
            timestamp_ns += fhp->time_delta;
        } else if (fhp->type_len == RINGBUF_TYPE_TIME_EXTEND) {
            std::uint32_t arr_0;
            MARK_CONSUMED(r_u32(buff, remaining, arr_0));
            std::uint64_t time_delta = arr_0;
            time_delta = (time_delta << 27);
            time_delta += fhp->time_delta;
            SPDLOG_TRACE(logger, "Time δ: +{}", time_delta);
            timestamp_ns += time_delta;
        } else {
            logger->error("Encounterd unkonwn type_len value {}", fhp->type_len);
            throw std::runtime_error("Got unknown type_len value");
        }
    }
    return CONSUMED;
}

#define READ_AND_DELIVER(event_type, rdr)                       \
    {                                                           \
        event_type e;                                           \
        assert(remaining >= rdr->repr_length());                \
        MARK_CONSUMED(rdr->read(buff, remaining, e));           \
        handler.handle(cpu, timestamp_ns, common_fields, e);    \
    }

std::size_t ftrace::EventReader::read_payload(const std::uint8_t* buff, std::size_t remaining, std::uint64_t timestamp_ns, std::int32_t cpu) const {
    auto buff_start = buff;

    event::CommonFields common_fields;
    assert(remaining > common_header_rdr->repr_length());
    MARK_CONSUMED(common_header_rdr->read(buff, remaining, common_fields));
    
    const auto type_id = common_fields.common_type;
    
    if (type_id == sys_entry_id) {
        READ_AND_DELIVER(event::SyscallEntry, sys_entry_rdr);
    } else if (type_id == sys_exit_id) {
        READ_AND_DELIVER(event::SyscallExit, sys_exit_rdr);
    } else if (type_id == sched_switch_id) {
        READ_AND_DELIVER(event::SchedSwitch, sched_switch_rdr);
    } else if (type_id == sched_wakeup_id) {
        READ_AND_DELIVER(event::SchedWakeup, sched_wakeup_rdr);
    } else {
        logger->error("Encountered event with unknown type-id: {}", type_id);
        throw std::runtime_error("Encountered event with unknown type-id");
    }
    return CONSUMED;
}

ftrace::PageReader::PageReader(const std::string& events_dir, const EventReader& _e_rdr, std::size_t _pg_sz) : e_rdr(_e_rdr), pg_sz(_pg_sz) {
    auto page_header_path = events_dir + "/header_page";
    auto page_header = Util::content(page_header_path, nullptr, nullptr);
    assert(page_header == EXPECTED_PAGE_HEADER);
}

ftrace::PageReader::~PageReader() {}

struct PgHeader {
    std::uint64_t ts;
    std::uint64_t data_len;
};

std::size_t ftrace::PageReader::read(std::int32_t cpu, const std::uint8_t* buff) {
    auto buff_start = buff;
    std::size_t remaining = pg_sz;
    auto pg_hdr = reinterpret_cast<const PgHeader*>(buff);
    MARK_CONSUMED(sizeof(PgHeader));
    assert(pg_hdr->data_len <= remaining);
    MARK_CONSUMED(e_rdr.read(cpu, pg_hdr->ts, buff, pg_hdr->data_len));
    auto consumed = CONSUMED;
    assert(consumed == (pg_hdr->data_len + sizeof(PgHeader)));
    return consumed;
}
