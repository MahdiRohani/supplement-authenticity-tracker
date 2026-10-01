package protocol

import (
	"context"
	"math/big"
	"strings"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/common/hexutil"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/audit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/merkle"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// ConsumeInput is a consume authorization signed with the unit key from the
// hidden label. The consumer needs no wallet balance: the relayer pays gas,
// and the signature, not the sender, authorizes the consume on-chain.
type ConsumeInput struct {
	// ChainID is optional; when present it must match the API's chain.
	ChainID   *int64
	BatchID   string
	Index     int64
	Consumer  string
	Deadline  int64
	Signature string
}

type ConsumeResult struct {
	Status      string `json:"status"`
	BatchID     string `json:"batchId"`
	Index       uint32 `json:"index"`
	SegmentID   string `json:"segmentId"`
	UnitKey     string `json:"unitKey"`
	Consumer    string `json:"consumer"`
	Submitter   string `json:"submitter"`
	TxHash      string `json:"txHash"`
	BlockNumber uint64 `json:"blockNumber"`
}

func (s *Service) Consume(ctx context.Context, in ConsumeInput) (ConsumeResult, error) {
	if in.ChainID != nil && *in.ChainID != s.opts.ChainID {
		return ConsumeResult{}, apperr.BadRequest("chainId does not match this deployment")
	}
	if in.Index < 0 || in.Index > int64(^uint32(0)) {
		return ConsumeResult{}, apperr.BadRequest("index must be a non-negative integer")
	}
	consumer := strings.TrimSpace(in.Consumer)
	if !common.IsHexAddress(consumer) || common.HexToAddress(consumer) == (common.Address{}) {
		return ConsumeResult{}, apperr.BadRequest("consumer must be a non-zero hex address")
	}
	if in.Deadline <= s.now().Unix() {
		return ConsumeResult{}, apperr.BadRequest("Authorization deadline expired")
	}
	signature, err := hexutil.Decode(strings.TrimSpace(in.Signature))
	if err != nil {
		return ConsumeResult{}, apperr.BadRequest("signature must be 0x-prefixed hex")
	}
	batch, index, err := s.batchAndIndex(ctx, in.BatchID, formatID(in.Index))
	if err != nil {
		return ConsumeResult{}, err
	}
	if batch.Invalid {
		return ConsumeResult{}, apperr.Conflict("Batch has been recalled")
	}

	auth := chain.UnitConsumeAuthorization{
		BatchID:  big.NewInt(batch.BatchID),
		Index:    index,
		Consumer: common.HexToAddress(consumer),
		Deadline: big.NewInt(in.Deadline),
		ChainID:  s.opts.ChainID,
	}
	signer, signature, err := s.eip712.RecoverUnitConsume(signature, auth)
	if err != nil {
		return ConsumeResult{}, apperr.BadRequest("Invalid consume signature: " + err.Error())
	}
	u, err := s.unit(ctx, batch, index)
	if err != nil {
		return ConsumeResult{}, err
	}
	if !strings.EqualFold(signer.Hex(), u.UnitKey) {
		return ConsumeResult{}, apperr.BadRequest("Consume authorization is not signed by this unit's key")
	}
	if u.ConsumedTxHash != nil {
		return ConsumeResult{}, apperr.Conflict("Unit already consumed; refill is not allowed")
	}
	proof, err := merkle.UnpackProof(u.Proof)
	if err != nil {
		return ConsumeResult{}, err
	}
	if !merkle.Verify(common.HexToHash(batch.MerkleRoot), merkle.UnitLeaf(index, signer), proof) {
		return ConsumeResult{}, apperr.BadRequest("Unit key is not part of this batch")
	}
	seg, err := s.store.FindSegmentForUnit(ctx, db.FindSegmentForUnitParams{BatchID: batch.BatchID, Index: int32(index)})
	if store.IsNotFound(err) {
		return ConsumeResult{}, apperr.ServiceUnavailable("Custody of this unit is not indexed yet")
	}
	if err != nil {
		return ConsumeResult{}, err
	}
	switch seg.Status {
	case db.SegmentStatusAtPointOfSale:
	case db.SegmentStatusInvalid:
		return ConsumeResult{}, apperr.Conflict("Batch has been recalled")
	default:
		return ConsumeResult{}, apperr.Conflict("Unit is not at a point of sale yet")
	}

	chainCtx, cancel := s.chainContext(ctx)
	defer cancel()
	event, err := s.registry.Consume(chainCtx, chain.ConsumeV2Input{
		BatchID:   batch.BatchID,
		SegmentID: seg.SegmentID,
		Index:     index,
		UnitKey:   signer,
		Consumer:  auth.Consumer,
		Deadline:  auth.Deadline,
		Proof:     proof,
		Signature: signature,
	})
	if err != nil {
		return ConsumeResult{}, err
	}
	s.project(ctx, "UnitConsumed", func() error { return s.projector.UnitConsumed(chainCtx, event) })

	result := ConsumeResult{
		Status:      "Consumed",
		BatchID:     formatID(event.BatchID),
		Index:       event.Index,
		SegmentID:   formatID(event.SegmentID),
		UnitKey:     event.UnitKey,
		Consumer:    event.Consumer,
		Submitter:   event.Submitter,
		TxHash:      event.TxHash,
		BlockNumber: event.BlockNumber,
	}
	s.record(chainCtx, audit.Entry{Action: "unit.consume", EntityID: result.BatchID + ":" + formatID(int64(index)), Actor: event.Consumer, Detail: result})
	return result, nil
}
