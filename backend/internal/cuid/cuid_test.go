package cuid

import (
	"regexp"
	"testing"
)

var format = regexp.MustCompile(`^c[0-9a-z]{24}$`)

func TestNewFormatAndUniqueness(t *testing.T) {
	seen := make(map[string]struct{}, 10_000)
	for range 10_000 {
		id := New()
		if !format.MatchString(id) {
			t.Fatalf("unexpected id %q", id)
		}
		if _, dup := seen[id]; dup {
			t.Fatalf("duplicate id %q", id)
		}
		seen[id] = struct{}{}
	}
}
