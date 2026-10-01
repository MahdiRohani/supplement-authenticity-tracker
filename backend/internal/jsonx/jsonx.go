// Package jsonx encodes JSON the way JavaScript's JSON.stringify does for the
// shapes this service emits. IPFS content hashes are computed over these bytes,
// so they must stay identical to what the previous Node.js backend produced.
package jsonx

import (
	"bytes"
	"encoding/json"
	"time"
)

// Marshal encodes v without HTML escaping and without a trailing newline.
func Marshal(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte("\n")), nil
}

// ISOTime formats t like Date.prototype.toISOString (UTC, millisecond precision).
func ISOTime(t time.Time) string {
	return t.UTC().Format("2006-01-02T15:04:05.000Z")
}

// Now returns the current UTC time truncated to the millisecond precision of
// the `timestamp(3)` columns.
func Now() time.Time {
	return time.Now().UTC().Truncate(time.Millisecond)
}
