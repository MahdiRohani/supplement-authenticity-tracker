package chain

import (
	"fmt"
	"math/big"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/core/types"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// EventPosition locates a log; segment updates are ordered by it.
type EventPosition struct {
	TxHash      string
	BlockNumber uint64
	LogIndex    uint
}

func positionOf(log types.Log) EventPosition {
	return EventPosition{TxHash: log.TxHash.Hex(), BlockNumber: log.BlockNumber, LogIndex: log.Index}
}

type BatchRegistered struct {
	EventPosition
	BatchID         int64
	SegmentID       int64
	Manufacturer    string
	Size            uint32
	MerkleRoot      string
	PhysicalBatchID string
	MetadataCID     string
	MetadataHash    string
}

// SegmentTransferred moved [Start, End) of FromSegmentID to To. When
// ToSegmentID differs from FromSegmentID the segment was split: the moved
// range became ToSegmentID and FromSegmentID now starts at End.
type SegmentTransferred struct {
	EventPosition
	BatchID       int64
	FromSegmentID int64
	ToSegmentID   int64
	From          string
	To            string
	Start         uint32
	End           uint32
	Status        db.SegmentStatus
}

func (e SegmentTransferred) Split() bool { return e.FromSegmentID != e.ToSegmentID }

type UnitConsumed struct {
	EventPosition
	// At is the block time; zero means "now" (a receipt just mined). Clone
	// detection compares scan times against it, so a backfill must not
	// stamp it with the indexing time.
	At        time.Time
	BatchID   int64
	Index     uint32
	SegmentID int64
	UnitKey   string
	Consumer  string
	Submitter string
}

type BatchInvalidated struct {
	EventPosition
	BatchID int64
	Actor   string
}

type SegmentInvalidated struct {
	EventPosition
	SegmentID int64
	BatchID   int64
	Actor     string
}

var segmentStatuses = []db.SegmentStatus{
	db.SegmentStatusCreated,
	db.SegmentStatusTransferred,
	db.SegmentStatusAtPointOfSale,
	db.SegmentStatusInvalid,
}

// SegmentStatusFromChain maps the contract's SegmentStatus enum.
func SegmentStatusFromChain(v any) (db.SegmentStatus, error) {
	n, ok := v.(uint8)
	if !ok || int(n) >= len(segmentStatuses) {
		return "", fmt.Errorf("unknown segment status %v", v)
	}
	return segmentStatuses[n], nil
}

func parseBatchRegistered(log types.Log, f map[string]any) (BatchRegistered, error) {
	batchID, err := int64Field(f, "batchId")
	if err != nil {
		return BatchRegistered{}, err
	}
	segmentID, err := int64Field(f, "segmentId")
	if err != nil {
		return BatchRegistered{}, err
	}
	size, _ := f["size"].(uint32)
	cid, _ := f["metadataCid"].(string)
	return BatchRegistered{
		EventPosition:   positionOf(log),
		BatchID:         batchID,
		SegmentID:       segmentID,
		Manufacturer:    lowerAddress(f["manufacturer"]),
		Size:            size,
		MerkleRoot:      hash32(f["merkleRoot"]),
		PhysicalBatchID: hash32(f["physicalBatchId"]),
		MetadataCID:     cid,
		MetadataHash:    hash32(f["metadataHash"]),
	}, nil
}

func parseSegmentTransferred(log types.Log, f map[string]any) (SegmentTransferred, error) {
	var out SegmentTransferred
	var err error
	if out.BatchID, err = int64Field(f, "batchId"); err != nil {
		return out, err
	}
	if out.FromSegmentID, err = int64Field(f, "fromSegmentId"); err != nil {
		return out, err
	}
	if out.ToSegmentID, err = int64Field(f, "toSegmentId"); err != nil {
		return out, err
	}
	if out.Status, err = SegmentStatusFromChain(f["status"]); err != nil {
		return out, err
	}
	out.EventPosition = positionOf(log)
	out.From = lowerAddress(f["from"])
	out.To = lowerAddress(f["to"])
	out.Start, _ = f["start"].(uint32)
	out.End, _ = f["end"].(uint32)
	return out, nil
}

func parseUnitConsumed(log types.Log, f map[string]any) (UnitConsumed, error) {
	batchID, err := int64Field(f, "batchId")
	if err != nil {
		return UnitConsumed{}, err
	}
	segmentID, err := int64Field(f, "segmentId")
	if err != nil {
		return UnitConsumed{}, err
	}
	index, _ := f["index"].(uint32)
	return UnitConsumed{
		EventPosition: positionOf(log),
		BatchID:       batchID,
		Index:         index,
		SegmentID:     segmentID,
		UnitKey:       lowerAddress(f["unitKey"]),
		Consumer:      lowerAddress(f["consumer"]),
		Submitter:     lowerAddress(f["submitter"]),
	}, nil
}

func parseBatchInvalidated(log types.Log, f map[string]any) (BatchInvalidated, error) {
	batchID, err := int64Field(f, "batchId")
	if err != nil {
		return BatchInvalidated{}, err
	}
	return BatchInvalidated{EventPosition: positionOf(log), BatchID: batchID, Actor: lowerAddress(f["actor"])}, nil
}

func parseSegmentInvalidated(log types.Log, f map[string]any) (SegmentInvalidated, error) {
	segmentID, err := int64Field(f, "segmentId")
	if err != nil {
		return SegmentInvalidated{}, err
	}
	batchID, err := int64Field(f, "batchId")
	if err != nil {
		return SegmentInvalidated{}, err
	}
	return SegmentInvalidated{EventPosition: positionOf(log), SegmentID: segmentID, BatchID: batchID, Actor: lowerAddress(f["actor"])}, nil
}

// int64Field reads a uint256 id. Registry counters start at 1 and grow by
// one per batch or split, so anything beyond int64 is a decoding error.
func int64Field(f map[string]any, name string) (int64, error) {
	n, ok := f[name].(*big.Int)
	if !ok {
		return 0, fmt.Errorf("event field %s: unexpected %T", name, f[name])
	}
	if !n.IsInt64() || n.Sign() < 0 {
		return 0, fmt.Errorf("event field %s: %s out of range", name, n)
	}
	return n.Int64(), nil
}

func bigID(id int64) *big.Int { return big.NewInt(id) }

func addressOrZero(s string) common.Address {
	if !common.IsHexAddress(s) {
		return common.Address{}
	}
	return common.HexToAddress(s)
}
