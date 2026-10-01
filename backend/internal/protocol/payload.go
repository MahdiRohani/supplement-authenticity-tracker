package protocol

import (
	"crypto/ecdsa"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"

	"github.com/ethereum/go-ethereum/crypto"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
)

// Every unit carries two QR codes. The public one is printed in the open and
// only identifies the unit; anyone may scan it to verify. The secret one sits
// under a scratch-off layer and holds the unit's private key, which signs the
// one-time consume authorization. Copying the public code therefore cannot
// consume or "refill" a unit, and copies show up as clone scans instead.
const secretScheme = "satk2"

// PublicQR is the verify URL printed on the open label.
func (s *Service) PublicQR(batchID int64, index uint32) string {
	return fmt.Sprintf("%s/%d/%d/%d", s.opts.PublicBaseURL, s.opts.ChainID, batchID, index)
}

// SecretQR encodes the hidden label: satk2:<chainId>:<batchId>:<index>:<key>.
func SecretQR(chainID, batchID int64, index uint32, key *ecdsa.PrivateKey) string {
	return fmt.Sprintf("%s:%d:%d:%d:%s", secretScheme, chainID, batchID, index, hex.EncodeToString(crypto.FromECDSA(key)))
}

type SecretPayload struct {
	ChainID int64
	BatchID int64
	Index   uint32
	Key     *ecdsa.PrivateKey
}

func ParseSecretQR(raw string) (SecretPayload, error) {
	invalid := apperr.BadRequest("Not a valid hidden unit label")
	parts := strings.Split(strings.TrimSpace(raw), ":")
	if len(parts) != 5 || parts[0] != secretScheme {
		return SecretPayload{}, invalid
	}
	chainID, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil || chainID <= 0 {
		return SecretPayload{}, invalid
	}
	batchID, err := ParseID("batchId", parts[2])
	if err != nil {
		return SecretPayload{}, invalid
	}
	index, err := ParseIndex(parts[3])
	if err != nil {
		return SecretPayload{}, invalid
	}
	keyHex := strings.TrimPrefix(parts[4], "0x")
	if len(keyHex) != 64 {
		return SecretPayload{}, invalid
	}
	key, err := crypto.HexToECDSA(keyHex)
	if err != nil {
		return SecretPayload{}, invalid
	}
	return SecretPayload{ChainID: chainID, BatchID: batchID, Index: index, Key: key}, nil
}
