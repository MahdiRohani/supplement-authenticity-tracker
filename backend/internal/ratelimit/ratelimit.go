// Package ratelimit implements an in-memory sliding-window limiter.
package ratelimit

import (
	"sync"
	"time"
)

type Limiter struct {
	mu   sync.Mutex
	hits map[string][]time.Time
	now  func() time.Time
}

func New() *Limiter {
	return &Limiter{hits: make(map[string][]time.Time), now: time.Now}
}

// Allow records a hit for key and reports whether it stays within limit hits
// per window.
func (l *Limiter) Allow(key string, limit int, window time.Duration) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := l.now()
	recent := l.hits[key][:0]
	for _, ts := range l.hits[key] {
		if now.Sub(ts) < window {
			recent = append(recent, ts)
		}
	}
	if len(recent) >= limit {
		l.hits[key] = recent
		return false
	}
	l.hits[key] = append(recent, now)
	return true
}

func (l *Limiter) Clear() {
	l.mu.Lock()
	defer l.mu.Unlock()
	clear(l.hits)
}
