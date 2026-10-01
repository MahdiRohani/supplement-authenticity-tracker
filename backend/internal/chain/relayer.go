package chain

import (
	"context"
	"crypto/ecdsa"
	"errors"
	"fmt"
	"math/big"
	"strings"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/core/types"
	"github.com/ethereum/go-ethereum/crypto"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
)

// Relayer submits registry transactions on behalf of managed wallets, paying
// gas with their configured keys.
type Relayer struct {
	contract *Contract
	keys     *KeyStore
	// setupErr explains why contract is nil (missing RPC_URL, address or ABI).
	setupErr error
}

// NewRelayer builds a relayer. When contract is nil every call fails with
// setupErr, which should be an *apperr.Error for configuration problems.
func NewRelayer(contract *Contract, keys *KeyStore, setupErr error) *Relayer {
	return &Relayer{contract: contract, keys: keys, setupErr: setupErr}
}

type RegisterUnitInput struct {
	ManufacturerAddress string
	SecretHash          string
	MetadataCID         string
	MetadataHash        string
	PhysicalID          string
}

type RegisteredUnit struct {
	ChainProductID string
	OwnerAddress   string
	TxHash         string
	BlockNumber    uint64
}

type RegisterBatchInput struct {
	ManufacturerAddress string
	SecretHashes        []string
	MetadataCID         string
	MetadataHash        string
	PhysicalIDs         []string
}

type RegisteredBatch struct {
	FirstChainProductID string
	Count               int
	OwnerAddress        string
	TxHash              string
	BlockNumber         uint64
}

type TxResult struct {
	Actor       string
	TxHash      string
	BlockNumber uint64
}

func (r *Relayer) RegisterUnit(ctx context.Context, in RegisterUnitInput) (RegisteredUnit, error) {
	manufacturer := strings.ToLower(in.ManufacturerAddress)
	key, ok := r.keys.Resolve(manufacturer)
	if !ok {
		return RegisteredUnit{}, apperr.BadRequest("No relayer key configured for manufacturer " + manufacturer)
	}
	contract, err := r.connect()
	if err != nil {
		return RegisteredUnit{}, err
	}
	secretHash, err := bytes32(in.SecretHash)
	if err != nil {
		return RegisteredUnit{}, fmt.Errorf("secretHash: %w", err)
	}
	metadataHash, err := bytes32(in.MetadataHash)
	if err != nil {
		return RegisteredUnit{}, fmt.Errorf("metadataHash: %w", err)
	}
	physicalID, err := bytes32(in.PhysicalID)
	if err != nil {
		return RegisteredUnit{}, fmt.Errorf("physicalId: %w", err)
	}

	receipt, err := contract.Transact(ctx, key, "registerUnit", secretHash, in.MetadataCID, metadataHash, physicalID)
	if err != nil {
		return RegisteredUnit{}, err
	}
	productID, err := r.registeredProductID(ctx, contract, receipt)
	if err != nil {
		return RegisteredUnit{}, err
	}
	return RegisteredUnit{
		ChainProductID: productID,
		OwnerAddress:   addressOf(key),
		TxHash:         receipt.TxHash.Hex(),
		BlockNumber:    receipt.BlockNumber.Uint64(),
	}, nil
}

func (r *Relayer) RegisterBatch(ctx context.Context, in RegisterBatchInput) (RegisteredBatch, error) {
	manufacturer := strings.ToLower(in.ManufacturerAddress)
	key, ok := r.keys.Resolve(manufacturer)
	if !ok {
		return RegisteredBatch{}, apperr.BadRequest("No relayer key configured for manufacturer " + manufacturer)
	}
	if len(in.SecretHashes) == 0 {
		return RegisteredBatch{}, apperr.BadRequest("secretHashes required")
	}
	if len(in.SecretHashes) != len(in.PhysicalIDs) {
		return RegisteredBatch{}, apperr.BadRequest("secretHashes and physicalIds length mismatch")
	}
	contract, err := r.connect()
	if err != nil {
		return RegisteredBatch{}, err
	}
	secretHashes, err := bytes32s(in.SecretHashes)
	if err != nil {
		return RegisteredBatch{}, fmt.Errorf("secretHashes: %w", err)
	}
	physicalIDs, err := bytes32s(in.PhysicalIDs)
	if err != nil {
		return RegisteredBatch{}, fmt.Errorf("physicalIds: %w", err)
	}
	metadataHash, err := bytes32(in.MetadataHash)
	if err != nil {
		return RegisteredBatch{}, fmt.Errorf("metadataHash: %w", err)
	}

	receipt, err := contract.Transact(ctx, key, "registerBatch", secretHashes, in.MetadataCID, metadataHash, physicalIDs)
	if err != nil {
		return RegisteredBatch{}, err
	}
	first, err := r.registeredProductID(ctx, contract, receipt)
	if err != nil {
		return RegisteredBatch{}, err
	}
	return RegisteredBatch{
		FirstChainProductID: first,
		Count:               len(in.SecretHashes),
		OwnerAddress:        addressOf(key),
		TxHash:              receipt.TxHash.Hex(),
		BlockNumber:         receipt.BlockNumber.Uint64(),
	}, nil
}

// TransferOwnership moves a minted product from ownerAddress to toAddress.
func (r *Relayer) TransferOwnership(ctx context.Context, ownerAddress, chainProductID, toAddress string) (TxResult, error) {
	key, ok := r.keys.Resolve(ownerAddress)
	if !ok {
		return TxResult{}, apperr.BadRequest("No relayer key configured for owner " + ownerAddress)
	}
	contract, err := r.connect()
	if err != nil {
		return TxResult{}, err
	}
	if !common.IsHexAddress(toAddress) {
		return TxResult{}, apperr.BadRequest("toAddress must be a hex address")
	}
	productID, ok := new(big.Int).SetString(chainProductID, 10)
	if !ok {
		return TxResult{}, apperr.BadRequest("Product " + chainProductID + " is not minted on-chain yet")
	}
	receipt, err := contract.Transact(ctx, key, "transferOwnership", productID, common.HexToAddress(toAddress))
	if err != nil {
		return TxResult{}, mapChainError(err, "")
	}
	return TxResult{Actor: addressOf(key), TxHash: receipt.TxHash.Hex(), BlockNumber: receipt.BlockNumber.Uint64()}, nil
}

// Consume marks a product consumed, signing with the owner's key or, when the
// owner is not a managed wallet, the first active relayer key.
func (r *Relayer) Consume(ctx context.Context, ownerAddress, chainProductID, secret string) (TxResult, error) {
	key, ok := r.keys.Resolve(ownerAddress)
	if !ok {
		key, ok = r.keys.First()
	}
	if !ok {
		return TxResult{}, apperr.BadRequest("No relayer key configured for consume")
	}
	contract, err := r.connect()
	if err != nil {
		return TxResult{}, err
	}
	productID, ok := new(big.Int).SetString(chainProductID, 10)
	if !ok {
		return TxResult{}, apperr.BadRequest("Product " + chainProductID + " is not minted on-chain yet")
	}
	secretBytes, err := bytes32(secret)
	if err != nil {
		return TxResult{}, apperr.BadRequest("secret must be a bytes32 hex string")
	}
	receipt, err := contract.Transact(ctx, key, "consume", productID, secretBytes)
	if err != nil {
		return TxResult{}, mapChainError(err, chainProductID)
	}
	return TxResult{Actor: addressOf(key), TxHash: receipt.TxHash.Hex(), BlockNumber: receipt.BlockNumber.Uint64()}, nil
}

func (r *Relayer) connect() (*Contract, error) {
	if r.contract == nil {
		if r.setupErr != nil {
			return nil, r.setupErr
		}
		return nil, errors.New("registry contract is not configured")
	}
	return r.contract, nil
}

// registeredProductID reads the id from the ProductRegistered event in the
// receipt, falling back to the contract's nextProductId counter.
func (r *Relayer) registeredProductID(ctx context.Context, contract *Contract, receipt *types.Receipt) (string, error) {
	for _, log := range receipt.Logs {
		fields, ok, err := contract.ParseEvent("ProductRegistered", *log)
		if err != nil || !ok {
			continue
		}
		if id, ok := fields["productId"].(*big.Int); ok {
			return id.String(), nil
		}
	}
	out, err := contract.Call(ctx, "nextProductId")
	if err != nil {
		return "", err
	}
	id, ok := out[0].(*big.Int)
	if !ok {
		return "", fmt.Errorf("nextProductId returned %T", out[0])
	}
	return id.String(), nil
}

// mapChainError translates known registry custom errors into client errors;
// anything else is left as an internal error.
func mapChainError(err error, productID string) error {
	name := ""
	var revert *RevertError
	if errors.As(err, &revert) {
		name = revert.Name
	}
	matches := func(candidate string) bool {
		return name == candidate || strings.Contains(err.Error(), candidate)
	}
	switch {
	case matches("ProductAlreadyConsumed"):
		return apperr.Conflict("Product already consumed; refill is not allowed")
	case matches("InvalidSecret"):
		return apperr.BadRequest("Invalid scratch secret")
	case matches("ProductNotConsumable"):
		return apperr.BadRequest(fmt.Sprintf("Product %s is not consumable in its current status", productID))
	case matches("EnforcedPause"):
		return apperr.BadRequest("Registry is paused")
	}
	return err
}

func addressOf(key *ecdsa.PrivateKey) string {
	return strings.ToLower(crypto.PubkeyToAddress(key.PublicKey).Hex())
}

func bytes32(s string) ([32]byte, error) {
	var out [32]byte
	b, err := hexutil.Decode(s)
	if err != nil {
		return out, err
	}
	if len(b) != 32 {
		return out, fmt.Errorf("expected 32 bytes, got %d", len(b))
	}
	copy(out[:], b)
	return out, nil
}

func bytes32s(values []string) ([][32]byte, error) {
	out := make([][32]byte, len(values))
	for i, v := range values {
		b, err := bytes32(v)
		if err != nil {
			return nil, fmt.Errorf("item %d: %w", i, err)
		}
		out[i] = b
	}
	return out, nil
}
