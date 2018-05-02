#include "fd_map.hh"

static const int INITIAL_MAP_SIZE = 1024;

void FdMapBase::put(map::KeyType key, Value* info) {
    add_to_values(info);
    Value *old = (Value*) map.put(key, (map::ValueType)info);
    if (old != nullptr)
        remove_and_release(old);
}

void FdMapBase::putFileInfo(fd_t fd, const char* path) {
    // constructor == call to acquire
    logger->info("File opened : '{}'", path);
    Value *info = new Value(FdInfo::file(path));
    put(toKey(fd), info);
}

void FdMapBase::putSocketInfo(fd_t fd, const char* remote_path, bool connect) {
    logger->info("Socket {} : '{}'", connect ? "connected" : "accepted", remote_path);
    Value *info = new Value(FdInfo::socket(remote_path, connect));
    put(toKey(fd), info);
}

FdMapBase::FdMapBase(int capacity) : LinkedMapBase(capacity) {
}

FdMapBase::Value* FdMapBase::get(fd_t fd) {
    Value* info = (Value*) map.get(toKey(fd));
    if (info != nullptr)
        info = info->acquire();
    return info;
}

void FdMapBase::remove(fd_t fd) {
    Value* info = (Value*) map.remove(toKey(fd));
    if (info != nullptr) {
        logger->info("{} closed : '{}'", info->data.type == File ? "File" : "Socket", info->data.targe_path);
        remove_and_release(info);
    }
}

/*
 * global fd_map
 */
FdMap fd_map(INITIAL_MAP_SIZE);

FdMap& getFdMap() {
    return fd_map;
}
