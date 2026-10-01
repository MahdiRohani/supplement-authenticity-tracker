package protocol

import (
	"math"
	"strings"
	"testing"

	"github.com/ethereum/go-ethereum/crypto"
)

func TestSecretQRRoundTrip(t *testing.T) {
	key, err := crypto.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	raw := SecretQR(31337, 42, 7, key)
	if !strings.HasPrefix(raw, "satk2:31337:42:7:") {
		t.Fatalf("payload = %s", raw)
	}
	got, err := ParseSecretQR("  " + raw + "\n")
	if err != nil {
		t.Fatal(err)
	}
	if got.ChainID != 31337 || got.BatchID != 42 || got.Index != 7 {
		t.Fatalf("parsed = %+v", got)
	}
	if crypto.PubkeyToAddress(got.Key.PublicKey) != crypto.PubkeyToAddress(key.PublicKey) {
		t.Fatal("key changed in the round trip")
	}
	withPrefix := strings.Replace(raw, ":7:", ":7:0x", 1)
	if _, err := ParseSecretQR(withPrefix); err != nil {
		t.Fatalf("0x-prefixed key rejected: %v", err)
	}
}

func TestParseSecretQRRejectsMalformedLabels(t *testing.T) {
	key := strings.Repeat("ab", 32)
	for _, raw := range []string{
		"",
		"satk1:1:1:0:" + key,
		"satk2:1:1:0",
		"satk2:1:1:0:" + key + ":extra",
		"satk2:0:1:0:" + key,
		"satk2:x:1:0:" + key,
		"satk2:1:0:0:" + key,
		"satk2:1:1:-1:" + key,
		"satk2:1:1:4294967296:" + key,
		"satk2:1:1:0:" + key[:62],
		"satk2:1:1:0:" + strings.Repeat("zz", 32),
		"satk2:1:1:0:" + strings.Repeat("00", 32),
		"https://example.com/u/1/1/0",
	} {
		if _, err := ParseSecretQR(raw); err == nil {
			t.Errorf("accepted %q", raw)
		}
	}
}

func TestPublicQR(t *testing.T) {
	s := &Service{opts: Options{ChainID: 11155111, PublicBaseURL: "https://x.ir/u"}}
	if got := s.PublicQR(3, 9); got != "https://x.ir/u/11155111/3/9" {
		t.Fatalf("public QR = %s", got)
	}
}

func TestParseIDAndIndex(t *testing.T) {
	for raw, want := range map[string]int64{"1": 1, " 42 ": 42, "000123": 123, "999999999999999999": 999999999999999999} {
		if got, err := ParseID("batchId", raw); err != nil || got != want {
			t.Errorf("ParseID(%q) = %d, %v", raw, got, err)
		}
	}
	for _, raw := range []string{"", "0", "-1", "+1", "1.5", "1e3", "abc", "9999999999999999999"} {
		if _, err := ParseID("batchId", raw); err == nil {
			t.Errorf("ParseID(%q) accepted", raw)
		}
	}
	for raw, want := range map[string]uint32{"0": 0, "17": 17, "4294967295": math.MaxUint32} {
		if got, err := ParseIndex(raw); err != nil || got != want {
			t.Errorf("ParseIndex(%q) = %d, %v", raw, got, err)
		}
	}
	for _, raw := range []string{"", "-1", "4294967296", "1.0", "x"} {
		if _, err := ParseIndex(raw); err == nil {
			t.Errorf("ParseIndex(%q) accepted", raw)
		}
	}
}

func TestPagingResolve(t *testing.T) {
	cases := []struct {
		in                  Paging
		page, limit, offset int64
	}{
		{Paging{math.NaN(), math.NaN()}, 1, 20, 0},
		{Paging{3, 10}, 3, 10, 20},
		{Paging{0, 1000}, 1, 100, 0},
		{Paging{2.9, 0}, 2, 1, 1},
		{Paging{math.Inf(1), 5}, 1, 5, 0},
		{Paging{math.MaxInt32, 100}, math.MaxInt32, 100, math.MaxInt32},
	}
	for _, c := range cases {
		page, limit, offset := c.in.resolve()
		if page != c.page || limit != c.limit || offset != c.offset {
			t.Errorf("%+v -> %d/%d/%d, want %d/%d/%d", c.in, page, limit, offset, c.page, c.limit, c.offset)
		}
	}
	if p := newPage(1, 20, 0, []int{}); p.TotalPages != 1 {
		t.Errorf("empty page count = %d", p.TotalPages)
	}
	if p := newPage(1, 20, 41, []int{}); p.TotalPages != 3 {
		t.Errorf("page count = %d", p.TotalPages)
	}
}

func TestDeviceHashIsKeyedAndStable(t *testing.T) {
	a := &Service{opts: Options{ScanSalt: "a"}}
	b := &Service{opts: Options{ScanSalt: "b"}}
	if a.DeviceHash("phone", "", "") != a.DeviceHash(" phone ", "1.2.3.4", "ua") {
		t.Error("an explicit device id must win over ip/user agent")
	}
	if a.DeviceHash("phone", "", "") == b.DeviceHash("phone", "", "") {
		t.Error("hash must depend on the salt")
	}
	if a.DeviceHash("", "1.2.3.4", "ua") == a.DeviceHash("", "1.2.3.5", "ua") {
		t.Error("fallback must distinguish clients")
	}
	if len(a.DeviceHash("x", "", "")) != 32 {
		t.Error("hash length")
	}
}

func TestBaseAuthenticityPrecedence(t *testing.T) {
	cases := []struct {
		recalled, consumed, pos bool
		want                    string
	}{
		{true, true, true, Recalled},
		{false, true, true, Consumed},
		{false, false, true, Authentic},
		{false, false, false, InTransit},
	}
	for _, c := range cases {
		if got := baseAuthenticity(c.recalled, c.consumed, c.pos); got != c.want {
			t.Errorf("%+v -> %s", c, got)
		}
	}
}
