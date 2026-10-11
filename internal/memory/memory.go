package memory

import "sync"

type MemoryStore struct {
	mu    sync.RWMutex
	Core  map[string]string
	Facts []string
	Logs  []string
}

var store = &MemoryStore{
	Core:  make(map[string]string),
	Facts: make([]string, 0),
	Logs:  make([]string, 0),
}

// Recall queries relevant memory from the 3-tier memory store
func Recall(query string) string {
	store.mu.RLock()
	defer store.mu.RUnlock()

	if name, ok := store.Core["user_name"]; ok && query == "name" {
		return name
	}
	return ""
}

// SaveFact adds a fact to memory
func SaveFact(fact string) {
	store.mu.Lock()
	defer store.mu.Unlock()
	store.Facts = append(store.Facts, fact)
}

