#include "mapping_parser.hh"

void MRegion::Parser::populate_rules() {
    using qi::int_;
    using qi::hex;
    using qi::ulong_;
    using qi::lit;
    using qi::double_;
    using qi::lexeme;
    using ascii::char_;

    hex_ %= +char_("a-fA-F0-9");

    range_ %= hex_ >> lit("-") >> hex_;

    perms_ %= +char_("-rwxps");

    dev_ %= hex >> lit(":") >> hex;

    region_ %= range_ >> perms_ >> ulong_ >> dev_ >> ulong_ >> *(char_);
}

MRegion::Parser::Parser(std::function<bool(const Event&)> _handler) : Parser::base_type(region_), handler(_handler) {
    populate_rules();
}

MRegion::Parser::~Parser() {}

bool MRegion::Parser::feed(std::istream& input) {
    std::string line;
    auto handler_ok = true;
    while (handler_ok && input.good()) {
        getline(input, line);
        if (line.empty()) continue;

        Event evt;
        Iter current = line.begin();
        Iter end = line.end();
        bool r = boost::spirit::qi::phrase_parse(current, end, *this, boost::spirit::ascii::space, evt);

        if (r && (current == end)) {
            handler_ok = handler(evt);
        }
    }
    return input.eof();
}
