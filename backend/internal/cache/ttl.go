// Package cache provides a small in-process TTL cache.
package cache

import (
	"sync"
	"time"
)

type entry[V any] struct {
	value     V
	expiresAt time.Time
}

// TTL is a concurrency-safe map whose entries expire after a per-entry TTL.
type TTL[V any] struct {
	mu    sync.Mutex
	items map[string]entry[V]
	now   func() time.Time
}

func NewTTL[V any]() *TTL[V] {
	return &TTL[V]{items: make(map[string]entry[V]), now: time.Now}
}

func (c *TTL[V]) Get(key string) (V, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.items[key]
	if !ok {
		var zero V
		return zero, false
	}
	if c.now().After(e.expiresAt) {
		delete(c.items, key)
		var zero V
		return zero, false
	}
	return e.value, true
}

func (c *TTL[V]) Set(key string, value V, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items[key] = entry[V]{value: value, expiresAt: c.now().Add(ttl)}
}

func (c *TTL[V]) Delete(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.items, key)
}

func (c *TTL[V]) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	clear(c.items)
}
