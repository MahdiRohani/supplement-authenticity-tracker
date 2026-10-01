package jsonx

import (
	"testing"
	"time"
)

func TestMarshalMatchesJSONStringify(t *testing.T) {
	got, err := Marshal(struct {
		Name  string  `json:"name"`
		Batch *string `json:"batch"`
	}{Name: `Vitamin <D3> & "Co" é`})
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != `{"name":"Vitamin <D3> & \"Co\" é","batch":null}` {
		t.Fatalf("got %s", got)
	}
}

func TestISOTime(t *testing.T) {
	ts := time.Date(2026, 1, 2, 3, 4, 5, 6_000_000, time.FixedZone("IRST", 3*3600+1800))
	if got := ISOTime(ts); got != "2026-01-01T23:34:05.006Z" {
		t.Fatalf("got %s", got)
	}
}
