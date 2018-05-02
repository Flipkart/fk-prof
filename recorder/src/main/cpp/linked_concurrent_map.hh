#ifndef LINKED_CONCURRENT_MAP_HH
#define LINKED_CONCURRENT_MAP_HH

#include "concurrent_map.hh"

/**
 * Extension to MapProvider to wrap the Data with the reference count.
 */
template <typename MapProvider, typename Data> 
class LinkedMapBase {

public:
    class Value {
        struct {
            Value* next;
            Value* prev;
        } links;
        
        friend class LinkedMapBase;
        
    public:
        Data data;
        std::atomic_int refs;
        
        template <typename... Args>
        Value(Args&&... args) : data(std::forward<Args>(args)...), refs(1) {}
        
        Value(const Data& _data) : data(_data), refs(1) {}
        
        void release() {
            int prev = refs.fetch_sub(1, std::memory_order_acquire);
            if (prev == 1) {
                delete this;
            }
        }
        
        Value* acquire() {
            int prev = refs.fetch_add(1, std::memory_order_relaxed);
            if (prev > 0) {
                return this;
            }
            refs.fetch_sub(1, std::memory_order_relaxed);
            return nullptr;
        }
    };    

protected:

    void remove_and_release(Value* b) {
        std::lock_guard<std::mutex> g(update_mutex);
        if (b->links.next != nullptr) b->links.next->links.prev = b->links.prev;
        if (b->links.prev != nullptr) b->links.prev->links.next = b->links.next;
        if (values == b) values = b->links.next;
        b->links.next = nullptr;
        b->links.prev = nullptr;
        b->release();
    }
    
    void add_to_values(Value* b) {
        std::lock_guard<std::mutex> g(update_mutex);
        b->links.prev = nullptr;
        b->links.next = values;
        if (values != nullptr) values->links.prev = b;
        values = b;
    }
    
    LinkedMapBase(int capacity) : map(capacity), values(nullptr) {
    }

    ~LinkedMapBase() {
        // clear the list
        while (values != nullptr) remove_and_release(values);
    }
    
    MapProvider map;
    
private:
    std::mutex update_mutex;
    Value* values;
};

#endif /* LINKED_CONCURRENT_MAP_HH */
