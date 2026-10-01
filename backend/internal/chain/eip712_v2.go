package chain

import (
	"crypto/ecdsa"
	"fmt"
	"math/big"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/signer/core/apitypes"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
)

// UnitConsumeAuthorization is the v2 ConsumeAuthorization message, signed by
// the unit's one-time key from the hidden label.
type UnitConsumeAuthorization struct {
	BatchID  *big.Int
	Index    uint32
	Consumer common.Address
	Deadline *big.Int
	ChainID  int64
}

// UnitConsumeTypeName is the EIP-712 primary type of a v2 consume.
const UnitConsumeTypeName = consumeTypeName

// UnitConsumeTypes describes the typed data so clients can sign it without
// hard-coding the schema.
func UnitConsumeTypes() map[string][]apitypes.Type {
	return map[string][]apitypes.Type{consumeTypeName: unitConsumeType}
}

// UnitConsumeDigest is the EIP-712 digest the contract recovers the unit key
// from (hashConsumeAuthorization on-chain).
func (e *EIP712) UnitConsumeDigest(a UnitConsumeAuthorization) (common.Hash, error) {
	if a.BatchID == nil || a.BatchID.Sign() <= 0 {
		return common.Hash{}, apperr.BadRequest("batchId must be a positive integer")
	}
	if a.Deadline == nil || a.Deadline.Sign() < 0 {
		return common.Hash{}, apperr.BadRequest("deadline must be a non-negative integer")
	}
	hash, err := e.hash(a.ChainID, consumeTypeName, unitConsumeType, apitypes.TypedDataMessage{
		"batchId":  a.BatchID,
		"index":    new(big.Int).SetUint64(uint64(a.Index)),
		"consumer": a.Consumer.Hex(),
		"deadline": a.Deadline,
	})
	if err != nil {
		return common.Hash{}, err
	}
	return common.BytesToHash(hash), nil
}

// SignUnitConsume signs with a unit key and returns the 65-byte signature
// with v = 27/28, which is what ECDSA.tryRecover expects.
func (e *EIP712) SignUnitConsume(unitKey *ecdsa.PrivateKey, a UnitConsumeAuthorization) ([]byte, error) {
	digest, err := e.UnitConsumeDigest(a)
	if err != nil {
		return nil, err
	}
	sig, err := crypto.Sign(digest[:], unitKey)
	if err != nil {
		return nil, fmt.Errorf("sign consume authorization: %w", err)
	}
	sig[64] += 27
	return sig, nil
}

// RecoverUnitConsume recovers the unit key that signed a. It applies the
// same rules as OpenZeppelin's ECDSA (65 bytes, low s, v in {27, 28}; v = 0/1
// is accepted and normalized) and returns the signature the contract will
// accept.
func (e *EIP712) RecoverUnitConsume(signature []byte, a UnitConsumeAuthorization) (common.Address, []byte, error) {
	if len(signature) != 65 {
		return common.Address{}, nil, apperr.BadRequest("signature must be 65 bytes")
	}
	normalized := append([]byte(nil), signature...)
	if normalized[64] < 27 {
		normalized[64] += 27
	}
	v := normalized[64] - 27
	r := new(big.Int).SetBytes(normalized[:32])
	s := new(big.Int).SetBytes(normalized[32:64])
	if !crypto.ValidateSignatureValues(v, r, s, true) {
		return common.Address{}, nil, apperr.BadRequest("signature is malformed or not in canonical low-s form")
	}
	digest, err := e.UnitConsumeDigest(a)
	if err != nil {
		return common.Address{}, nil, err
	}
	raw := append(append([]byte(nil), normalized[:64]...), v)
	pub, err := crypto.SigToPub(digest[:], raw)
	if err != nil {
		return common.Address{}, nil, apperr.BadRequest("signature cannot be recovered")
	}
	return crypto.PubkeyToAddress(*pub), normalized, nil
}
