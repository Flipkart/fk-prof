#ifndef UNIQUE_READSAFE_PTR_H
#define UNIQUE_READSAFE_PTR_H

#include <memory>
#include <cstdint>

template <typename T> class UniqueReadsafePtr;

template <typename T> class ReadsafePtr {
private:
    UniqueReadsafePtr<T>& ptr;
    bool ref_count_incremented;
    bool _available;

public:
    ReadsafePtr(UniqueReadsafePtr<T>& _ptr) : ptr(_ptr), ref_count_incremented(false) {
        if ((_available = ptr.available.load(std::memory_order_seq_cst))) {
            ptr.reader_count.fetch_add(1, std::memory_order_seq_cst);
            ref_count_incremented = true;
            _available = ptr.available.load(std::memory_order_seq_cst);
        }
    }

    ~ReadsafePtr() {
        if (ref_count_incremented) {
            ptr.reader_count.fetch_sub(1, std::memory_order_seq_cst);
        }
    }

    bool available() { return _available; }

    T* operator->() {
        return _available ? ptr.ptr.get() : nullptr;
    }

private:
    ReadsafePtr(const ReadsafePtr&);
    void operator=(const ReadsafePtr&);
};

template <typename T> class UniqueReadsafePtr {
private:
    std::unique_ptr<T> ptr;
    std::atomic<bool> available;
    std::atomic<std::uint32_t> reader_count;

    friend class ReadsafePtr<T>;

public:
    UniqueReadsafePtr() : available(false), reader_count(0) {}

    ~UniqueReadsafePtr() {
        reset();
    }

    void reset(T* t = nullptr) {
        available.store(false, std::memory_order_seq_cst);
        while (reader_count.load(std::memory_order_seq_cst) > 0);
        ptr.reset(t);
        if (t != nullptr) available.store(true, std::memory_order_seq_cst);
    }

private:
    UniqueReadsafePtr(const UniqueReadsafePtr&);
    void operator=(const UniqueReadsafePtr&);
};

#endif
