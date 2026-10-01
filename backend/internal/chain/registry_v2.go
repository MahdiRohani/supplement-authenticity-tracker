package chain

import (
	"context"
	"crypto/ecdsa"
	"errors"
	"fmt"
	"math/big"
	"strings"

	"github.com/ethereum/go-ethereum/accounts/abi"
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/core/types"
	"github.com/ethereum/go-ethereum/crypto"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// RegistryV2 relays SupplementRegistryV2 writes with managed wallet keys and
// serves direct reads. All methods fail with the setup error when the
// contract is not configured.
type RegistryV2 struct {
	contract *Contract
	keys     *KeyStore
	setupErr error
}

func NewRegistryV2(contract *Contract, keys *KeyStore, setupErr error) *RegistryV2 {
	return &RegistryV2{contract: contract, keys: keys, setupErr: setupErr}
}

// Address is the checksummed registry address, or "" when unconfigured.
func (r *RegistryV2) Address() string {
	if r == nil || r.contract == nil {
		return ""
	}
	return r.contract.Address().Hex()
}

func (r *RegistryV2) Contract() *Contract {
	if r == nil {
		return nil
	}
	return r.contract
}

func (r *RegistryV2) connect() (*Contract, error) {
	if r == nil || r.contract == nil {
		if r != nil && r.setupErr != nil {
			return nil, r.setupErr
		}
		return nil, apperr.ServiceUnavailable("SupplementRegistryV2 is not configured")
	}
	return r.contract, nil
}

func (r *RegistryV2) keyFor(address, purpose string) (*ecdsa.PrivateKey, error) {
	key, ok := r.keys.Resolve(address)
	if !ok {
		return nil, apperr.BadRequest(fmt.Sprintf("No relayer key configured for %s %s", purpose, strings.ToLower(address)))
	}
	return key, nil
}

type RegisterBatchV2Input struct {
	Manufacturer    string
	MerkleRoot      common.Hash
	Size            uint32
	MetadataCID     string
	MetadataHash    common.Hash
	PhysicalBatchID common.Hash
}

func (r *RegistryV2) RegisterBatch(ctx context.Context, in RegisterBatchV2Input) (BatchRegistered, error) {
	contract, err := r.connect()
	if err != nil {
		return BatchRegistered{}, err
	}
	key, err := r.keyFor(in.Manufacturer, "manufacturer")
	if err != nil {
		return BatchRegistered{}, err
	}
	receipt, err := contract.Transact(ctx, key, "registerBatch",
		in.MerkleRoot, in.Size, in.MetadataCID, in.MetadataHash, in.PhysicalBatchID)
	if err != nil {
		return BatchRegistered{}, mapV2Error(err)
	}
	return firstEvent(contract, receipt, "BatchRegistered", parseBatchRegistered)
}

// TransferSegment moves count units (0 = all) of segmentID from owner to to.
func (r *RegistryV2) TransferSegment(ctx context.Context, owner string, segmentID int64, to string, count uint32) (SegmentTransferred, error) {
	contract, err := r.connect()
	if err != nil {
		return SegmentTransferred{}, err
	}
	if !common.IsHexAddress(to) {
		return SegmentTransferred{}, apperr.BadRequest("toAddress must be a hex address")
	}
	key, err := r.keyFor(owner, "segment owner")
	if err != nil {
		return SegmentTransferred{}, err
	}
	receipt, err := contract.Transact(ctx, key, "transferSegment", bigID(segmentID), common.HexToAddress(to), count)
	if err != nil {
		return SegmentTransferred{}, mapV2Error(err)
	}
	return firstEvent(contract, receipt, "SegmentTransferred", parseSegmentTransferred)
}

type ConsumeV2Input struct {
	BatchID   int64
	SegmentID int64
	Index     uint32
	UnitKey   common.Address
	Consumer  common.Address
	Deadline  *big.Int
	Proof     []common.Hash
	Signature []byte
}

// consumeRequest mirrors the ConsumeRequest tuple; go-ethereum matches the
// fields by their camel-cased ABI names.
type consumeRequest struct {
	BatchId   *big.Int
	SegmentId *big.Int
	Index     uint32
	UnitKey   common.Address
	Consumer  common.Address
	Deadline  *big.Int
}

// Consume submits a consumer-authorized consume, paying gas with the first
// active relayer key: the signature, not the sender, authorizes it.
func (r *RegistryV2) Consume(ctx context.Context, in ConsumeV2Input) (UnitConsumed, error) {
	contract, err := r.connect()
	if err != nil {
		return UnitConsumed{}, err
	}
	key, ok := r.keys.First()
	if !ok {
		return UnitConsumed{}, apperr.ServiceUnavailable("No relayer key configured for consume")
	}
	proof := make([][32]byte, len(in.Proof))
	for i, h := range in.Proof {
		proof[i] = h
	}
	req := consumeRequest{
		BatchId:   bigID(in.BatchID),
		SegmentId: bigID(in.SegmentID),
		Index:     in.Index,
		UnitKey:   in.UnitKey,
		Consumer:  in.Consumer,
		Deadline:  in.Deadline,
	}
	receipt, err := contract.Transact(ctx, key, "consume", req, proof, in.Signature)
	if err != nil {
		return UnitConsumed{}, mapV2Error(err)
	}
	return firstEvent(contract, receipt, "UnitConsumed", parseUnitConsumed)
}

// InvalidateBatch recalls a batch, signing with the first of actors that has
// a configured key (the contract accepts the batch manufacturer or an admin).
func (r *RegistryV2) InvalidateBatch(ctx context.Context, actors []string, batchID int64) (BatchInvalidated, error) {
	contract, key, err := r.invalidator(actors)
	if err != nil {
		return BatchInvalidated{}, err
	}
	receipt, err := contract.Transact(ctx, key, "invalidateBatch", bigID(batchID))
	if err != nil {
		return BatchInvalidated{}, mapV2Error(err)
	}
	return firstEvent(contract, receipt, "BatchInvalidated", parseBatchInvalidated)
}

func (r *RegistryV2) InvalidateSegment(ctx context.Context, actors []string, segmentID int64) (SegmentInvalidated, error) {
	contract, key, err := r.invalidator(actors)
	if err != nil {
		return SegmentInvalidated{}, err
	}
	receipt, err := contract.Transact(ctx, key, "invalidateSegment", bigID(segmentID))
	if err != nil {
		return SegmentInvalidated{}, mapV2Error(err)
	}
	return firstEvent(contract, receipt, "SegmentInvalidated", parseSegmentInvalidated)
}

func (r *RegistryV2) invalidator(actors []string) (*Contract, *ecdsa.PrivateKey, error) {
	contract, err := r.connect()
	if err != nil {
		return nil, nil, err
	}
	for _, actor := range actors {
		if key, ok := r.keys.Resolve(actor); ok {
			return contract, key, nil
		}
	}
	if key, ok := r.keys.First(); ok {
		return contract, key, nil
	}
	return nil, nil, apperr.ServiceUnavailable("No relayer key configured for recall")
}

// onChainRoles maps off-chain role names to SupplementRegistryV2 roles.
var onChainRoles = map[string]common.Hash{
	"Manufacturer": crypto.Keccak256Hash([]byte("MANUFACTURER_ROLE")),
	"Distributor":  crypto.Keccak256Hash([]byte("DISTRIBUTOR_ROLE")),
	"Pharmacy":     crypto.Keccak256Hash([]byte("PHARMACY_ROLE")),
	"Admin":        {},
}

type RoleGrant struct {
	Role        string  `json:"role"`
	AlreadyHeld bool    `json:"alreadyHeld"`
	TxHash      *string `json:"txHash"`
}

// GrantRole grants a supply-chain role on-chain, signing with the first
// managed key that holds DEFAULT_ADMIN_ROLE; it is a no-op when the account
// already has the role.
func (r *RegistryV2) GrantRole(ctx context.Context, role, account string) (RoleGrant, error) {
	contract, err := r.connect()
	if err != nil {
		return RoleGrant{}, err
	}
	roleID, ok := onChainRoles[role]
	if !ok {
		return RoleGrant{}, apperr.BadRequest("Unknown role: " + role)
	}
	if !common.IsHexAddress(account) {
		return RoleGrant{}, apperr.BadRequest("address must be a hex wallet address")
	}
	target := common.HexToAddress(account)
	held, err := r.hasRole(ctx, contract, roleID, target)
	if err != nil {
		return RoleGrant{}, err
	}
	if held {
		return RoleGrant{Role: role, AlreadyHeld: true}, nil
	}
	for _, addr := range r.keys.Addresses() {
		admin, err := r.hasRole(ctx, contract, common.Hash{}, common.HexToAddress(addr))
		if err != nil {
			return RoleGrant{}, err
		}
		if !admin {
			continue
		}
		key, _ := r.keys.Resolve(addr)
		receipt, err := contract.Transact(ctx, key, "grantRole", roleID, target)
		if err != nil {
			return RoleGrant{}, mapV2Error(err)
		}
		tx := receipt.TxHash.Hex()
		return RoleGrant{Role: role, TxHash: &tx}, nil
	}
	return RoleGrant{}, apperr.ServiceUnavailable("No relayer key with the registry admin role is configured")
}

func (r *RegistryV2) hasRole(ctx context.Context, contract *Contract, role common.Hash, account common.Address) (bool, error) {
	out, err := contract.Call(ctx, "hasRole", role, account)
	if err != nil {
		return false, err
	}
	held, _ := out[0].(bool)
	return held, nil
}

type BatchView struct {
	BatchID         int64
	MerkleRoot      string
	MetadataHash    string
	PhysicalBatchID string
	Manufacturer    string
	Size            uint32
	ConsumedCount   uint32
	Invalid         bool
	MetadataCID     string
}

type batchTuple struct {
	MerkleRoot      [32]byte
	MetadataHash    [32]byte
	PhysicalBatchId [32]byte
	Manufacturer    common.Address
	Size            uint32
	ConsumedCount   uint32
	Invalid         bool
	MetadataCid     string
}

// GetBatch reads a batch; ok is false when it does not exist.
func (r *RegistryV2) GetBatch(ctx context.Context, batchID int64) (BatchView, bool, error) {
	contract, err := r.connect()
	if err != nil {
		return BatchView{}, false, err
	}
	out, err := contract.Call(ctx, "getBatch", bigID(batchID))
	if isRevert(err, "BatchDoesNotExist") {
		return BatchView{}, false, nil
	}
	if err != nil {
		return BatchView{}, false, err
	}
	t := *abi.ConvertType(out[0], new(batchTuple)).(*batchTuple)
	return BatchView{
		BatchID:         batchID,
		MerkleRoot:      hexutil.Encode(t.MerkleRoot[:]),
		MetadataHash:    hexutil.Encode(t.MetadataHash[:]),
		PhysicalBatchID: hexutil.Encode(t.PhysicalBatchId[:]),
		Manufacturer:    strings.ToLower(t.Manufacturer.Hex()),
		Size:            t.Size,
		ConsumedCount:   t.ConsumedCount,
		Invalid:         t.Invalid,
		MetadataCID:     t.MetadataCid,
	}, true, nil
}

type SegmentView struct {
	SegmentID int64
	BatchID   int64
	Owner     string
	Start     uint32
	End       uint32
	Status    db.SegmentStatus
}

type segmentTuple struct {
	BatchId *big.Int
	Owner   common.Address
	Start   uint32
	End     uint32
	Status  uint8
}

func (r *RegistryV2) GetSegment(ctx context.Context, segmentID int64) (SegmentView, bool, error) {
	contract, err := r.connect()
	if err != nil {
		return SegmentView{}, false, err
	}
	out, err := contract.Call(ctx, "getSegment", bigID(segmentID))
	if isRevert(err, "SegmentDoesNotExist") {
		return SegmentView{}, false, nil
	}
	if err != nil {
		return SegmentView{}, false, err
	}
	t := *abi.ConvertType(out[0], new(segmentTuple)).(*segmentTuple)
	status, err := SegmentStatusFromChain(t.Status)
	if err != nil {
		return SegmentView{}, false, err
	}
	if !t.BatchId.IsInt64() {
		return SegmentView{}, false, fmt.Errorf("segment %d batch id out of range", segmentID)
	}
	return SegmentView{
		SegmentID: segmentID,
		BatchID:   t.BatchId.Int64(),
		Owner:     strings.ToLower(t.Owner.Hex()),
		Start:     t.Start,
		End:       t.End,
		Status:    status,
	}, true, nil
}

func (r *RegistryV2) IsConsumed(ctx context.Context, batchID int64, index uint32) (bool, error) {
	contract, err := r.connect()
	if err != nil {
		return false, err
	}
	out, err := contract.Call(ctx, "isConsumed", bigID(batchID), index)
	if err != nil {
		return false, err
	}
	consumed, _ := out[0].(bool)
	return consumed, nil
}

func firstEvent[T any](contract *Contract, receipt *types.Receipt, name string, parse func(types.Log, map[string]any) (T, error)) (T, error) {
	var zero T
	for _, log := range receipt.Logs {
		if log.Address != contract.Address() {
			continue
		}
		fields, ok, err := contract.ParseEvent(name, *log)
		if err != nil {
			return zero, err
		}
		if ok {
			return parse(*log, fields)
		}
	}
	return zero, fmt.Errorf("receipt %s has no %s event", receipt.TxHash.Hex(), name)
}

func isRevert(err error, name string) bool {
	var revert *RevertError
	return err != nil && errors.As(err, &revert) && revert.Name == name
}

// v2Errors maps registry custom errors to client-facing errors.
var v2Errors = map[string]func() error{
	"PhysicalBatchIdAlreadyRegistered": func() error { return apperr.Conflict("A batch with this lot code is already registered") },
	"InvalidBatchSize":                 func() error { return apperr.BadRequest("Batch size is out of range") },
	"BatchDoesNotExist":                func() error { return apperr.NotFound("Batch not found on-chain") },
	"SegmentDoesNotExist":              func() error { return apperr.NotFound("Segment not found on-chain") },
	"BatchIsInvalid":                   func() error { return apperr.Conflict("Batch has been recalled") },
	"NotSegmentOwner":                  func() error { return apperr.BadRequest("The relaying wallet does not own this segment") },
	"InvalidTransfer": func() error {
		return apperr.BadRequest("Transfer not allowed: the recipient lacks the next custody role")
	},
	"InvalidTransferCount":             func() error { return apperr.BadRequest("count exceeds the units in this segment") },
	"SegmentBatchMismatch":             func() error { return apperr.BadRequest("Segment belongs to another batch") },
	"SegmentNotConsumable":             func() error { return apperr.Conflict("Unit is not at a point of sale yet") },
	"IndexOutOfRange":                  func() error { return apperr.BadRequest("Unit index is outside the batch") },
	"IndexOutOfSegment":                func() error { return apperr.BadRequest("Unit index is outside the segment") },
	"UnitAlreadyConsumed":              func() error { return apperr.Conflict("Unit already consumed; refill is not allowed") },
	"InvalidMerkleProof":               func() error { return apperr.BadRequest("Unit key is not part of this batch") },
	"InvalidUnitSignature":             func() error { return apperr.BadRequest("Consume authorization is not signed by the unit key") },
	"AuthorizationExpired":             func() error { return apperr.BadRequest("Authorization deadline expired") },
	"InvalidConsumer":                  func() error { return apperr.BadRequest("consumer must not be the zero address") },
	"NotAuthorizedToInvalidate":        func() error { return apperr.BadRequest("Only the manufacturer or an admin can recall") },
	"SegmentNotInvalidatable":          func() error { return apperr.Conflict("Segment is already recalled") },
	"AccessControlUnauthorizedAccount": func() error { return apperr.BadRequest("The relaying wallet lacks the required role") },
	"EnforcedPause":                    func() error { return apperr.ServiceUnavailable("Registry is paused") },
}

func mapV2Error(err error) error {
	var revert *RevertError
	if errors.As(err, &revert) {
		if mapped, ok := v2Errors[revert.Name]; ok {
			return mapped()
		}
	}
	return err
}
