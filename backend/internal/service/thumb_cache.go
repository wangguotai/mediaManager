package service

import (
	"container/list"
	"sync"
)

// ThumbCache is an in-memory LRU cache for thumbnail byte slices.
// It caps the entry count at maxItems and rejects individual entries
// larger than maxItemBytes. This avoids repeated disk reads for
// frequently requested thumbnails.
type ThumbCache struct {
	mu          sync.Mutex
	maxItems    int
	maxItemSize int
	items       map[string]*list.Element
	order       *list.List // front = most recently used
	totalBytes  int
}

// thumbEntry is the value stored in the LRU list/map.
type thumbEntry struct {
	key   string
	mime  string
	width int32
	height int32
	data  []byte
}

// NewThumbCache creates a ThumbCache with the given item cap and per-item byte limit.
func NewThumbCache(maxItems, maxItemSize int) *ThumbCache {
	return &ThumbCache{
		maxItems:    maxItems,
		maxItemSize: maxItemSize,
		items:       make(map[string]*list.Element),
		order:       list.New(),
	}
}

// Get retrieves a cached thumbnail by key. Returns the data, mime type,
// dimensions, and true on hit; nil values and false on miss.
func (c *ThumbCache) Get(key string) ([]byte, string, int32, int32, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.items[key]
	if !ok {
		return nil, "", 0, 0, false
	}
	c.order.MoveToFront(el)
	entry := el.Value.(*thumbEntry)
	return entry.data, entry.mime, entry.width, entry.height, true
}

// Put stores a thumbnail in the cache. Entries exceeding maxItemSize are
// silently dropped to prevent a single large thumbnail from evicting
// many small ones. If the key already exists, its value is replaced.
func (c *ThumbCache) Put(key, mime string, width, height int32, data []byte) {
	if c == nil || len(data) > c.maxItemSize {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()

	// If key exists, update in place.
	if el, ok := c.items[key]; ok {
		oldEntry := el.Value.(*thumbEntry)
		c.totalBytes -= len(oldEntry.data)
		oldEntry.mime = mime
		oldEntry.width = width
		oldEntry.height = height
		oldEntry.data = data
		c.totalBytes += len(data)
		c.order.MoveToFront(el)
		return
	}

	// Evict least-recently-used entries until we have room.
	for len(c.items) >= c.maxItems && c.order.Len() > 0 {
		c.evictLRU()
	}

	entry := &thumbEntry{
		key:    key,
		mime:   mime,
		width:  width,
		height: height,
		data:   data,
	}
	el := c.order.PushFront(entry)
	c.items[key] = el
	c.totalBytes += len(data)
}

// Invalidate removes a specific key from the cache.
func (c *ThumbCache) Invalidate(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if el, ok := c.items[key]; ok {
		entry := el.Value.(*thumbEntry)
		c.totalBytes -= len(entry.data)
		c.order.Remove(el)
		delete(c.items, key)
	}
}

// Len returns the current number of cached entries.
func (c *ThumbCache) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.items)
}

// evictLRU removes the least recently used entry. Caller must hold the lock.
func (c *ThumbCache) evictLRU() {
	back := c.order.Back()
	if back == nil {
		return
	}
	entry := back.Value.(*thumbEntry)
	c.totalBytes -= len(entry.data)
	c.order.Remove(back)
	delete(c.items, entry.key)
}
