#ifndef FD_MAP_HH
#define FD_MAP_HH

#include "linked_concurrent_map.hh"
#include "globals.hh"

typedef std::int32_t fd_t;

enum FdType { File, Socket };

uiid_t next_fd_uiid();

struct FdInfo {
    const FdType type;
    const std::string targe_path;
    const bool connect; // to distinguish between connected/accepted socket connection
    const uiid_t uiid;  // unique instance id

    static FdInfo file(const char *path) {
        return FdInfo{File, std::string(path), false, next_fd_uiid()};
    }

    static FdInfo socket(const char *remote_path, bool connect) {
        return FdInfo{Socket, std::string(remote_path), connect, next_fd_uiid()};
    }
};

struct TrivialIntHasher {
    static int64_t hash(void *p) {
        return (int64_t)p;
    }
};

class FdMapBase
    : public LinkedMapBase<map::ConcurrentMapProvider<TrivialIntHasher, false>, FdInfo> {

    void put(map::KeyType key, Value *info);

    map::KeyType toKey(fd_t fd) {
        auto p = static_cast<uintptr_t>(fd);
        return (map::KeyType)p;
    }

public:
    FdMapBase(int capacity);

    void putFileInfo(fd_t fd, const char *path);

    void putSocketInfo(fd_t fd, const char *remote_path, bool connect);

    Value *get(fd_t fd);

    void remove(fd_t fd);
};

typedef FdMapBase FdMap;
typedef FdMap::Value FdBucket;
FdMap &getFdMap();

#endif /* FD_MAP_HH */
