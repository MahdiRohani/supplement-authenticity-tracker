package cache

import (
	"testing"
	"time"
)

func TestTTLExpiresEntries(t *testing.T) {
	now := time.Unix(0, 0)
	c := NewTTL[string]()
	c.now = func() time.Time { return now }

	c.Set("a", "1", time.Second)
	if v, ok := c.Get("a"); !ok || v != "1" {
		t.Fatalf("get = %q %v", v, ok)
	}
	now = now.Add(time.Second)
	if _, ok := c.Get("a"); !ok {
		t.Fatal("entry must live for the full TTL")
	}
	now = now.Add(time.Millisecond)
	if _, ok := c.Get("a"); ok {
		t.Fatal("entry should have expired")
	}
}

func TestTTLDeleteAndClear(t *testing.T) {
	c := NewTTL[int]()
	c.Set("a", 1, time.Minute)
	c.Set("b", 2, time.Minute)
	c.Delete("a")
	if _, ok := c.Get("a"); ok {
		t.Fatal("deleted entry still present")
	}
	c.Clear()
	if _, ok := c.Get("b"); ok {
		t.Fatal("cleared entry still present")
	}
}
