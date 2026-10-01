package chain

import (
	"fmt"
	"math/big"
	"strings"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/common/math"
	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/signer/core/apitypes"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
)

var (
	domainType = []apitypes.Type{
		{Name: "name", Type: "string"},
		{Name: "version", Type: "string"},
		{Name: "chainId", Type: "uint256"},
		{Name: "verifyingContract", Type: "address"},
	}
	metadataType = []apitypes.Type{
		{Name: "metadataHash", Type: "bytes32"},
		{Name: "manufacturer", Type: "address"},
		{Name: "chainId", Type: "uint256"},
		{Name: "nonce", Type: "uint256"},
	}
	consumeType = []apitypes.Type{
		{Name: "productId", Type: "uint256"},
		{Name: "secret", Type: "bytes32"},
		{Name: "consumer", Type: "address"},
		{Name: "deadline", Type: "uint256"},
	}
)

// EIP712 signs and verifies typed data under the SupplementRegistry domain.
type EIP712 struct {
	verifyingContract string
}

// NewEIP712 uses registryAddress as the domain's verifyingContract, or the
// zero address when it is empty.
func NewEIP712(registryAddress string) *EIP712 {
	if strings.TrimSpace(registryAddress) == "" {
		registryAddress = common.Address{}.Hex()
	}
	return &EIP712{verifyingContract: registryAddress}
}

type ManufacturerMetadata struct {
	MetadataHash string
	Manufacturer string
	ChainID      int64
	Nonce        int64
}

type ConsumeAuthorization struct {
	ProductID string
	Secret    string
	Consumer  string
	Deadline  int64
	ChainID   int64
}

// SignManufacturerMetadata returns a 65-byte hex signature (v = 27/28), the
// same encoding ethers' Wallet.signTypedData produces.
func (e *EIP712) SignManufacturerMetadata(privateKey string, m ManufacturerMetadata) (string, error) {
	keyBytes, err := hexutil.Decode(ensure0x(privateKey))
	if err != nil {
		return "", apperr.BadRequest("privateKey must be a hex string")
	}
	key, err := crypto.ToECDSA(keyBytes)
	if err != nil {
		return "", apperr.BadRequest("privateKey is not a valid secp256k1 key")
	}
	hash, err := e.metadataHash(m)
	if err != nil {
		return "", err
	}
	sig, err := crypto.Sign(hash, key)
	if err != nil {
		return "", fmt.Errorf("sign typed data: %w", err)
	}
	sig[64] += 27
	return hexutil.Encode(sig), nil
}

func (e *EIP712) RecoverManufacturerMetadata(signature string, m ManufacturerMetadata) (common.Address, error) {
	hash, err := e.metadataHash(m)
	if err != nil {
		return common.Address{}, err
	}
	return recoverSigner(hash, signature)
}

func (e *EIP712) RecoverConsumeAuthorization(signature string, a ConsumeAuthorization) (common.Address, error) {
	productID, ok := new(big.Int).SetString(a.ProductID, 10)
	if !ok {
		return common.Address{}, apperr.BadRequest("productId must be a decimal integer")
	}
	secret, err := typedBytes32("secret", a.Secret)
	if err != nil {
		return common.Address{}, err
	}
	if !common.IsHexAddress(a.Consumer) {
		return common.Address{}, apperr.BadRequest("consumer must be a hex address")
	}
	hash, err := e.hash(a.ChainID, "ConsumeAuthorization", consumeType, apitypes.TypedDataMessage{
		"productId": productID,
		"secret":    secret,
		"consumer":  a.Consumer,
		"deadline":  big.NewInt(a.Deadline),
	})
	if err != nil {
		return common.Address{}, err
	}
	return recoverSigner(hash, signature)
}

func (e *EIP712) metadataHash(m ManufacturerMetadata) ([]byte, error) {
	metadataHash, err := typedBytes32("metadataHash", m.MetadataHash)
	if err != nil {
		return nil, err
	}
	if !common.IsHexAddress(m.Manufacturer) {
		return nil, apperr.BadRequest("manufacturer must be a hex address")
	}
	return e.hash(m.ChainID, "ManufacturerMetadata", metadataType, apitypes.TypedDataMessage{
		"metadataHash": metadataHash,
		"manufacturer": m.Manufacturer,
		"chainId":      big.NewInt(m.ChainID),
		"nonce":        big.NewInt(m.Nonce),
	})
}

func (e *EIP712) hash(chainID int64, primaryType string, fields []apitypes.Type, message apitypes.TypedDataMessage) ([]byte, error) {
	typed := apitypes.TypedData{
		Types: apitypes.Types{
			"EIP712Domain": domainType,
			primaryType:    fields,
		},
		PrimaryType: primaryType,
		Domain: apitypes.TypedDataDomain{
			Name:              "SupplementRegistry",
			Version:           "1",
			ChainId:           math.NewHexOrDecimal256(chainID),
			VerifyingContract: e.verifyingContract,
		},
		Message: message,
	}
	hash, _, err := apitypes.TypedDataAndHash(typed)
	if err != nil {
		return nil, fmt.Errorf("hash typed data: %w", err)
	}
	return hash, nil
}

// recoverSigner accepts 65-byte (v = 0/1 or 27/28) and 64-byte EIP-2098
// compact signatures.
func recoverSigner(hash []byte, signature string) (common.Address, error) {
	raw, err := hexutil.Decode(ensure0x(signature))
	if err != nil {
		return common.Address{}, apperr.BadRequest("signature must be a hex string")
	}
	sig := make([]byte, 65)
	switch len(raw) {
	case 65:
		copy(sig, raw)
		if sig[64] >= 27 {
			sig[64] -= 27
		}
	case 64:
		copy(sig, raw[:32])
		copy(sig[32:64], raw[32:64])
		sig[64] = raw[32] >> 7
		sig[32] &= 0x7f
	default:
		return common.Address{}, apperr.BadRequest("signature must be 64 or 65 bytes")
	}
	if sig[64] > 1 {
		return common.Address{}, apperr.BadRequest("signature has an invalid recovery id")
	}
	pub, err := crypto.SigToPub(hash, sig)
	if err != nil {
		return common.Address{}, apperr.BadRequest("signature cannot be recovered")
	}
	return crypto.PubkeyToAddress(*pub), nil
}

func typedBytes32(field, value string) (hexutil.Bytes, error) {
	b, err := hexutil.Decode(value)
	if err != nil || len(b) != 32 {
		return nil, apperr.BadRequest(field + " must be a 32-byte hex string")
	}
	return b, nil
}

func ensure0x(s string) string {
	if strings.HasPrefix(s, "0x") || strings.HasPrefix(s, "0X") {
		return s
	}
	return "0x" + s
}
