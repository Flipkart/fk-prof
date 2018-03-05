#ifndef LSTACK_HH
#define LSTACK_HH

#include <stdint.h>
#include <atomic>
#include <assert.h>

/**
 * Lock free stack
 * Maintains a linked list of free regions and a pool of Entries, where i'th node corresponds to
 * i'th entry.
 */
template <typename Entry>
class LFStack {

    struct Node {
        Node *next;
    };

    struct Head {
        uintptr_t aba;
        Node *node;
    };

    static_assert(sizeof(Head) % 16 == 0, "");

public:
    void push(Entry *value) {
        Node *node = node_for(value);
        Head next, orig = free.load();
        do {
            node->next = orig.node;
            next.aba = orig.aba + 1;
            next.node = node;
        } while (!free.compare_exchange_weak(orig, next));
    }

    Entry *pop() {
        Head next, orig = free.load();
        do {
            if (orig.node == nullptr) {
                return nullptr;
            }

            next.aba = orig.aba + 1;
            next.node = orig.node->next;
        } while (!free.compare_exchange_weak(orig, next));
        return entry_for(orig.node);
    }

    explicit LFStack(std::size_t per_entry_sz, std::size_t max_sz) : free(Head{0, nullptr}) {
        max_size = max_sz;
        per_entry_size = per_entry_sz;

        nodes = new Node[max_sz];
        values = new Entry[per_entry_sz * max_sz];

        for (auto i = 0; i < max_size - 1; ++i) {
            nodes[i].next = nodes + i + 1;
        }
        nodes[max_size - 1].next = nullptr;

        free.store(Head{0, nodes});
    }

    ~LFStack() {
        // check if we need to check for some conditions here
        // may be some nodes in use.
        delete[] values;
        delete[] nodes;
    }

private:
    std::atomic<Head> free;
    Node *nodes;
    Entry *values;

    std::size_t per_entry_size;
    std::size_t max_size;

    Entry *entry_for(Node *node) const noexcept {
        if (node == nullptr)
            return nullptr;
        return values + ((nodes - node) * per_entry_size);
    }

    Node *node_for(Entry *value) const noexcept {
        return nodes + ((values - value) / per_entry_size);
    }
};

template <typename Entry>
using Node_t = typename LFStack<Entry>::Node;

template <typename Entry>
using Head_t = typename LFStack<Entry>::Head;

#endif /* LSTACK_HH */
