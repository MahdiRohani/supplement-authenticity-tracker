package ratelimit

import (
	"testing"
	"time"
)

func TestBlocksAfterLimitInWindow(t *testing.T) {
	l := New()
	if !l.Allow("verify:1", 2, time.Minute) || !l.Allow("verify:1", 2, time.Minute) {
		t.Fatal("first two hits must pass")
	}
	if l.Allow("verify:1", 2, time.Minute) {
		t.Fatal("third hit must be blocked")
	}
	if !l.Allow("verify:2", 2, time.Minute) {
		t.Fatal("keys are limited independently")
	}
}

func TestWindowSlides(t *testing.T) {
	now := time.Unix(0, 0)
	l := New()
	l.now = func() time.Time { return now }

	l.Allow("k", 1, time.Second)
	if l.Allow("k", 1, time.Second) {
		t.Fatal("expected block inside the window")
	}
	now = now.Add(time.Second)
	if !l.Allow("k", 1, time.Second) {
		t.Fatal("hit should pass once the window has passed")
	}
}

func TestClear(t *testing.T) {
	l := New()
	l.Allow("k", 1, time.Minute)
	l.Clear()
	if !l.Allow("k", 1, time.Minute) {
		t.Fatal("clear should reset counters")
	}
}
