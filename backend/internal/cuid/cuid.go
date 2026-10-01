// Package cuid generates collision-resistant ids in the cuid (v1) format that
// Prisma's `@default(cuid())` produced, so new rows look like existing ones.
package cuid

import (
	"crypto/rand"
	"encoding/binary"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

const (
	blockSize = 4
	discrete  = 36 * 36 * 36 * 36
)

var (
	counter     atomic.Uint32
	fingerprint = computeFingerprint()
)

// New returns a new 25-character id such as "clxk2v9ab0000a1b2c3d4e5f6".
func New() string {
	var b strings.Builder
	b.Grow(25)
	b.WriteByte('c')
	b.WriteString(strconv.FormatInt(time.Now().UnixMilli(), 36))
	b.WriteString(pad(strconv.FormatUint(uint64((counter.Add(1)-1)%discrete), 36), blockSize))
	b.WriteString(fingerprint)
	b.WriteString(randomBlock())
	b.WriteString(randomBlock())
	return b.String()
}

func randomBlock() string {
	var buf [4]byte
	if _, err := rand.Read(buf[:]); err != nil {
		panic("cuid: crypto/rand unavailable: " + err.Error())
	}
	return pad(strconv.FormatUint(uint64(binary.BigEndian.Uint32(buf[:])%discrete), 36), blockSize)
}

func computeFingerprint() string {
	pid := pad(strconv.FormatInt(int64(os.Getpid()), 36), 2)
	host, _ := os.Hostname()
	sum := len(host) + 36
	for _, r := range host {
		sum += int(r)
	}
	return pid + pad(strconv.FormatInt(int64(sum), 36), 2)
}

func pad(s string, size int) string {
	if len(s) >= size {
		return s[len(s)-size:]
	}
	return strings.Repeat("0", size-len(s)) + s
}
