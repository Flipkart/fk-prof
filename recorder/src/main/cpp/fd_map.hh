#ifndef FD_MAP_HH
#define FD_MAP_HH

#include "linked_concurrent_map.hh"
#include "globals.hh"

typedef std::int32_t fd_type_t;

enum FdType {
    File, Socket
};

struct FdInfo {
    FdType type;
    std::string targetPath;
    bool connect;       // to distinguish between connected/accepted socket connection
    
    static FdInfo file(const char* path) {
        return FdInfo {File, std::string(path), false};
    }
    
    static FdInfo socket(const char* remotePath, bool connect) {
        return FdInfo {Socket, std::string(remotePath), connect};
    }
};

struct TrivialIntHasher {
    static int64_t hash(void *p) {
        return (int64_t)p;
    }
};

class FdMapBase : public LinkedMapBase<map::ConcurrentMapProvider<TrivialIntHasher, false>, FdInfo> {

    void put(map::KeyType key, Value* info);
    
    map::KeyType toKey(fd_type_t fd) {
        auto p = static_cast<uintptr_t>(fd);
        return (map::KeyType) p;
    }

public:
    FdMapBase(int capacity);
    
    void putFileInfo(fd_type_t fd, const char* path);
    
    void putSocketInfo(fd_type_t fd, const char* remotePath, bool connect);

    Value* get(fd_type_t fd);

    void remove(fd_type_t fd);
};

typedef FdMapBase FdMap;
typedef FdMap::Value FdBucket;
FdMap& get_fd_map();

#endif /* FD_MAP_HH */
