package service

import (
	"container/list"
	"sync"
	"sync/atomic"
)

// ThumbCache is an in-memory LRU cache for thumbnail byte slices.
// It caps both the entry count (maxItems, safety valve) and the aggregate
// memory footprint (maxBytes). Eviction is byte-driven: once totalBytes
// exceeds maxBytes the least-recently-used entries are evicted until the
// budget is satisfied. Individual entries larger than maxItemSize are
// rejected to prevent a single thumbnail from evicting many small ones.
// This avoids repeated disk reads for frequently requested thumbnails.
//
// Hit/miss counters (hits, misses) are tracked with atomic operations so
// that the /api/stats endpoint can report cache hit rate without locking.
type ThumbCache struct {
	mu          sync.Mutex
	maxItems    int
	maxBytes    int
	maxItemSize int
	items       map[string]*list.Element
	order       *list.List // front = most recently used
	totalBytes  int

	// Atomic hit/miss counters for observability via /api/stats.
	hits   int64
	misses int64
}

// thumbEntry is the value stored in the LRU list/map.
type thumbEntry struct {
	key   string
	mime  string
	width int32
	height int32
	data  []byte
}

// NewThumbCache creates a ThumbCache with the given item cap, total byte
// budget, and per-item byte limit. maxBytes controls the primary eviction
// trigger; maxItems is a safety valve to prevent unbounded key growth from
// many tiny entries.
func NewThumbCache(maxItems, maxBytes, maxItemSize int) *ThumbCache {
	return &ThumbCache{
		maxItems:    maxItems,
		maxBytes:    maxBytes,
		maxItemSize: maxItemSize,
		items:       make(map[string]*list.Element),
		order:       list.New(),
	}
}

// Get retrieves a cached thumbnail by key. Returns the data, mime type,
// dimensions, and true on hit; nil values and false on miss.
// Increments the atomic hit or miss counter for stats reporting.
func (c *ThumbCache) Get(key string) ([]byte, string, int32, int32, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.items[key]
	if !ok {
		atomic.AddInt64(&c.misses, 1)
		return nil, "", 0, 0, false
	}
	atomic.AddInt64(&c.hits, 1)
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

	// Evict least-recently-used entries until we are within both the byte
	// budget and the item cap. Byte budget is the primary trigger; the item
	// cap is a safety valve for many tiny entries.
	for (c.totalBytes+len(data) > c.maxBytes || len(c.items) >= c.maxItems) && c.order.Len() > 0 {
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

// Stats returns the current cache statistics for the /api/stats endpoint.
// Includes hit/miss counts, hit rate, entry count, and memory usage.
func (c *ThumbCache) Stats() ThumbCacheStats {
	c.mu.Lock()
	defer c.mu.Unlock()
	hits := atomic.LoadInt64(&c.hits)
	misses := atomic.LoadInt64(&c.misses)
	total := hits + misses
	var hitRate float64
	if total > 0 {
		hitRate = float64(hits) / float64(total) * 100
	}
	return ThumbCacheStats{
		Hits:       hits,
		Misses:     misses,
		HitRate:    hitRate,
		Items:      len(c.items),
		MaxItems:   c.maxItems,
		TotalBytes: c.totalBytes,
		MaxBytes:   c.maxBytes,
	}
}

// ThumbCacheStats is a snapshot of thumbnail cache statistics.
type ThumbCacheStats struct {
	Hits       int64   `json:"hits"`
	Misses     int64   `json:"misses"`
	HitRate    float64 `json:"hit_rate_percent"`
	Items      int     `json:"items"`
	MaxItems   int     `json:"max_items"`
	TotalBytes int     `json:"total_bytes"`
	MaxBytes   int     `json:"max_bytes"`
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
