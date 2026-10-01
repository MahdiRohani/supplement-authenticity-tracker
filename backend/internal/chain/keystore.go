package chain

import (
	"bytes"
	"crypto/ecdsa"
	"encoding/json"
	"fmt"
	"strings"
	"sync"

	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/crypto"
)

// KeyStore maps managed wallet addresses to relayer private keys. Keys removed
// from the active set stay usable as "previous" until the next reload, which
// allows zero-downtime rotation.
type KeyStore struct {
	mu       sync.RWMutex
	active   orderedKeys
	previous map[string]string
}

// orderedKeys keeps the configuration order, which decides the fallback key
// for consume and the order of addresses reported after a reload.
type orderedKeys struct {
	addresses []string
	keys      map[string]string
}

func NewKeyStore() *KeyStore {
	return &KeyStore{active: orderedKeys{keys: map[string]string{}}, previous: map[string]string{}}
}

// Reload swaps in a new active set parsed from activeJSON; the old active set
// and previousJSON become the fallback set. It returns the active addresses.
func (s *KeyStore) Reload(activeJSON, previousJSON string) ([]string, error) {
	active, err := parseKeys(activeJSON)
	if err != nil {
		return nil, fmt.Errorf("RELAYER_KEYS_JSON: %w", err)
	}
	previous := map[string]string{}
	if previousJSON != "" {
		extra, err := parseKeys(previousJSON)
		if err != nil {
			return nil, fmt.Errorf("RELAYER_KEYS_PREVIOUS_JSON: %w", err)
		}
		for addr, key := range extra.keys {
			previous[addr] = key
		}
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	for addr, key := range s.active.keys {
		if _, overridden := previous[addr]; !overridden {
			previous[addr] = key
		}
	}
	s.active = active
	s.previous = previous
	return append([]string(nil), active.addresses...), nil
}

func (s *KeyStore) Counts() (active, previous int) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.active.addresses), len(s.previous)
}

// Resolve returns the key configured for address, checking the active set
// first.
func (s *KeyStore) Resolve(address string) (*ecdsa.PrivateKey, bool) {
	normalized := strings.ToLower(address)
	s.mu.RLock()
	raw, ok := s.active.keys[normalized]
	if !ok {
		raw, ok = s.previous[normalized]
	}
	s.mu.RUnlock()
	if !ok {
		return nil, false
	}
	return toECDSA(raw)
}

// Addresses lists the active addresses in configuration order.
func (s *KeyStore) Addresses() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]string(nil), s.active.addresses...)
}

// First returns the first configured active key.
func (s *KeyStore) First() (*ecdsa.PrivateKey, bool) {
	s.mu.RLock()
	if len(s.active.addresses) == 0 {
		s.mu.RUnlock()
		return nil, false
	}
	raw := s.active.keys[s.active.addresses[0]]
	s.mu.RUnlock()
	return toECDSA(raw)
}

func toECDSA(raw string) (*ecdsa.PrivateKey, bool) {
	b, err := hexutil.Decode(raw)
	if err != nil {
		return nil, false
	}
	key, err := crypto.ToECDSA(b)
	if err != nil {
		return nil, false
	}
	return key, true
}

// parseKeys decodes a JSON object of address -> private key, preserving key
// order.
func parseKeys(raw string) (orderedKeys, error) {
	out := orderedKeys{keys: map[string]string{}}
	dec := json.NewDecoder(bytes.NewReader([]byte(raw)))
	tok, err := dec.Token()
	if err != nil {
		return out, err
	}
	if delim, ok := tok.(json.Delim); !ok || delim != '{' {
		return out, fmt.Errorf("expected a JSON object")
	}
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			return out, err
		}
		address := strings.ToLower(tok.(string))
		var key string
		if err := dec.Decode(&key); err != nil {
			return out, fmt.Errorf("key for %s: %w", address, err)
		}
		if _, seen := out.keys[address]; !seen {
			out.addresses = append(out.addresses, address)
		}
		out.keys[address] = key
	}
	if _, err := dec.Token(); err != nil {
		return out, err
	}
	return out, nil
}
