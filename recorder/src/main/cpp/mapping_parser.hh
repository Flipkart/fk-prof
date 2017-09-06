#ifndef MAPPING_PARSER_H
#define MAPPING_PARSER_H

#include <boost/config/warning_disable.hpp>
#include <boost/spirit/include/qi.hpp>
#include <boost/spirit/include/phoenix_core.hpp>
#include <boost/spirit/include/phoenix_operator.hpp>
#include <boost/spirit/include/phoenix_object.hpp>
#include <boost/fusion/include/adapt_struct.hpp>
#include <boost/fusion/include/io.hpp>
#include <boost/variant.hpp>

namespace MRegion {
    struct Range {
        std::string start;
        std::string end;
    };

    struct Dev {
        std::uint32_t maj;
        std::uint32_t min;
    };

    struct Region {
        Range range;
        std::string perms;
        std::uint64_t offset;
        Dev dev;
        std::uint64_t inode;
        std::string path;
    };
}

BOOST_FUSION_ADAPT_STRUCT(
    MRegion::Range,
    (std::string, start)
    (std::string, end))

BOOST_FUSION_ADAPT_STRUCT(
    MRegion::Dev,
    (std::uint32_t, maj)
    (std::uint32_t, min))

BOOST_FUSION_ADAPT_STRUCT(
    MRegion::Region,
    (MRegion::Range, range)
    (std::string, perms)
    (std::uint64_t, offset)
    (MRegion::Dev, dev)
    (std::uint64_t, inode)
    (std::string, path))

namespace MRegion {
    namespace qi = boost::spirit::qi;
    namespace ascii = boost::spirit::ascii;

    typedef Region Event;
    typedef std::string::const_iterator Iter;

    struct Parser : qi::grammar<Iter, Event(), ascii::space_type> {
    private:
        const std::function<bool(const Event&)> handler;

        qi::rule<Iter, std::string()> hex_;
        qi::rule<Iter, Range(), ascii::space_type> range_;
        qi::rule<Iter, std::string()> perms_;
        qi::rule<Iter, Dev(), ascii::space_type> dev_;
        qi::rule<Iter, Region(), ascii::space_type> region_;

        void populate_rules();

    public:
        Parser(std::function<bool(const Event&)> _handler);
        ~Parser();

        bool feed(std::istream& input);
    };
}

#endif
