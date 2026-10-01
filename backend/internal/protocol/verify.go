package protocol

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strconv"
	"strings"
	"time"

	"github.com/ethereum/go-ethereum/common"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/merkle"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/risk"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// Verification outcomes, from the buyer's point of view.
const (
	// Authentic: registered, at a pharmacy, not yet used.
	Authentic = "Authentic"
	// InTransit: genuine but not yet released for sale by a pharmacy.
	InTransit = "InTransit"
	// Consumed: this unit was already used; a sealed box carrying its label
	// is a refill or a copy.
	Consumed = "Consumed"
	Recalled = "Recalled"
	// Suspicious: genuine registration, but the scan pattern of this label
	// indicates it has been copied.
	Suspicious = "Suspicious"
)

type VerifyInput struct {
	ChainID string
	BatchID string
	Index   string
	// Device identifies the scanning app install; when empty the client IP
	// and user agent stand in for it. It is only stored as a keyed hash.
	Device    string
	ClientIP  string
	UserAgent string
	Region    string
	// Fresh bypasses cached batch state.
	Fresh bool
}

type Product struct {
	Name               *string `json:"name"`
	LotCode            *string `json:"lotCode"`
	Manufacturer       *Party  `json:"manufacturer"`
	MetadataCID        string  `json:"metadataCid"`
	MetadataGatewayURL string  `json:"metadataGatewayUrl"`
}

type ChainEvidence struct {
	UnitKey    string   `json:"unitKey"`
	MerkleRoot string   `json:"merkleRoot"`
	Leaf       string   `json:"leaf"`
	Proof      []string `json:"proof"`
	RegisterTx string   `json:"registerTxHash"`
}

type VerifyResult struct {
	Authenticity  string          `json:"authenticity"`
	ChainID       int64           `json:"chainId"`
	BatchID       string          `json:"batchId"`
	Index         uint32          `json:"index"`
	Product       Product         `json:"product"`
	Custodian     *Party          `json:"custodian"`
	SegmentID     *string         `json:"segmentId"`
	SegmentStatus *string         `json:"segmentStatus"`
	Consumed      bool            `json:"consumed"`
	Consumption   *Consumption    `json:"consumption"`
	Recalled      bool            `json:"recalled"`
	Risk          risk.Assessment `json:"risk"`
	ScanCount     int             `json:"scanCount"`
	Evidence      *ChainEvidence  `json:"evidence"`
	CheckedAt     string          `json:"checkedAt"`
}

// Verify answers a public label scan. Every scan is recorded (with a hashed
// device id) and scored for clone risk against the unit's scan history.
func (s *Service) Verify(ctx context.Context, in VerifyInput) (VerifyResult, error) {
	chainID, err := strconv.ParseInt(strings.TrimSpace(in.ChainID), 10, 64)
	if err != nil || chainID != s.opts.ChainID {
		return VerifyResult{}, apperr.NotFound("This label belongs to another network")
	}
	batchID, err := ParseID("batchId", in.BatchID)
	if err != nil {
		return VerifyResult{}, err
	}
	index, err := ParseIndex(in.Index)
	if err != nil {
		return VerifyResult{}, err
	}
	snap, err := s.snapshot(ctx, batchID, in.Fresh)
	if err != nil {
		return VerifyResult{}, err
	}
	batch := snap.batch
	if int64(index) >= int64(batch.Size) {
		return VerifyResult{}, apperr.NotFound("Unit is outside this batch")
	}

	result := VerifyResult{
		ChainID: s.opts.ChainID,
		BatchID: formatID(batchID),
		Index:   index,
		Product: Product{
			Name:               batch.Name,
			LotCode:            batch.LotCode,
			Manufacturer:       s.manufacturer(ctx, snap),
			MetadataCID:        batch.MetadataCID,
			MetadataGatewayURL: s.pinner.GatewayURL(batch.MetadataCID),
		},
		Recalled:  batch.Invalid,
		CheckedAt: jsonx.ISOTime(s.now()),
	}

	var ownerRegion *string
	seg, hasSegment := snap.segmentFor(index)
	if hasSegment {
		result.SegmentID = new(formatID(seg.SegmentID))
		result.SegmentStatus = new(string(seg.Status))
		result.Custodian = snap.parties[seg.Owner]
		if result.Custodian != nil {
			ownerRegion = result.Custodian.Region
		}
		result.Recalled = result.Recalled || seg.Status == db.SegmentStatusInvalid
	}

	u, err := s.store.GetUnit(ctx, db.GetUnitParams{BatchID: batchID, Index: int32(index)})
	switch {
	case err == nil:
		result.Consumed = u.ConsumedTxHash != nil
		if result.Consumed {
			result.Consumption = &Consumption{Consumer: u.Consumer, TxHash: *u.ConsumedTxHash, BlockNumber: u.ConsumedBlock, At: isoPtr(u.ConsumedAt)}
		}
		result.Evidence = evidence(batch, index, u)
	case !store.IsNotFound(err):
		return VerifyResult{}, err
	}

	base := baseAuthenticity(result.Recalled, result.Consumed, hasSegment && seg.Status == db.SegmentStatusAtPointOfSale)
	result.Risk, result.ScanCount = s.scoreScan(ctx, in, batchID, index, u.ConsumedAt, ownerRegion, base)
	result.Authenticity = base
	if result.Risk.Level == risk.LevelHigh && (base == Authentic || base == InTransit) {
		result.Authenticity = Suspicious
	}
	return result, nil
}

func baseAuthenticity(recalled, consumed, atPointOfSale bool) string {
	switch {
	case recalled:
		return Recalled
	case consumed:
		return Consumed
	case atPointOfSale:
		return Authentic
	default:
		return InTransit
	}
}

// scoreScan records the scan, then scores the unit's history including it.
// Scan bookkeeping never fails a verification: on error the result carries
// a low, reason-less assessment.
func (s *Service) scoreScan(ctx context.Context, in VerifyInput, batchID int64, index uint32, consumedAt *time.Time, ownerRegion *string, status string) (risk.Assessment, int) {
	scanID := cuid.New()
	region := risk.NormalizeRegion(in.Region)
	err := s.store.InsertScanEvent(ctx, db.InsertScanEventParams{
		ID:           scanID,
		BatchID:      batchID,
		Index:        int32(index),
		DeviceHash:   s.DeviceHash(in.Device, in.ClientIP, in.UserAgent),
		Region:       region,
		StatusAtScan: status,
		Now:          jsonx.Now(),
	})
	if err != nil {
		s.log.WarnContext(ctx, "scan event not recorded", "err", err)
		return risk.Assess(risk.Signals{}, s.opts.Risk), 0
	}
	row, err := s.store.UnitScanSignals(ctx, db.UnitScanSignalsParams{BatchID: batchID, Index: int32(index), ConsumedAt: consumedAt, OwnerRegion: ownerRegion})
	if err != nil {
		s.log.WarnContext(ctx, "scan signals unavailable", "err", err)
		return risk.Assess(risk.Signals{}, s.opts.Risk), 0
	}
	signals := risk.Signals{
		TotalScans:             int(row.TotalScans),
		DevicesBeforeConsume:   int(row.DevicesBeforeConsume),
		ScansAfterConsume:      int(row.ScansAfterConsume),
		NewDevicesAfterConsume: int(row.NewDevicesAfterConsume),
		DistinctRegions:        int(row.DistinctRegions),
		ForeignRegions:         int(row.ForeignRegions),
		OwnerRegionKnown:       ownerRegion != nil && *ownerRegion != "",
		Consumed:               consumedAt != nil,
	}
	assessment := risk.Assess(signals, s.opts.Risk)
	if assessment.Level == risk.LevelHigh && (status == Authentic || status == InTransit) {
		status = Suspicious
	}
	if err := s.store.SetScanRisk(ctx, db.SetScanRiskParams{ID: scanID, RiskScore: assessment.Score, StatusAtScan: status}); err != nil {
		s.log.WarnContext(ctx, "scan risk not stored", "err", err)
	}
	return assessment, signals.TotalScans
}

// DeviceHash keys device identifiers with the deployment salt so stored
// scans cannot be joined with other datasets.
func (s *Service) DeviceHash(device, ip, userAgent string) string {
	id := strings.TrimSpace(device)
	if id == "" {
		id = "ip:" + ip + "|ua:" + userAgent
	}
	mac := hmac.New(sha256.New, []byte(s.opts.ScanSalt))
	mac.Write([]byte(id))
	return hex.EncodeToString(mac.Sum(nil))[:32]
}

func evidence(batch db.Batch, index uint32, u db.Unit) *ChainEvidence {
	proof, err := merkle.UnpackProof(u.Proof)
	if err != nil || u.Proof == nil {
		return nil
	}
	hexProof := make([]string, len(proof))
	for i, h := range proof {
		hexProof[i] = h.Hex()
	}
	return &ChainEvidence{
		UnitKey:    u.UnitKey,
		MerkleRoot: batch.MerkleRoot,
		Leaf:       merkle.UnitLeaf(index, common.HexToAddress(u.UnitKey)).Hex(),
		Proof:      hexProof,
		RegisterTx: batch.TxHash,
	}
}

func (s *Service) manufacturer(ctx context.Context, snap batchSnapshot) *Party {
	if p, ok := snap.parties[snap.batch.Manufacturer]; ok {
		return p
	}
	party := &Party{Address: snap.batch.Manufacturer}
	if profile, err := s.store.GetPartyProfile(ctx, snap.batch.Manufacturer); err == nil {
		party.Role = new(string(profile.Role))
		party.DisplayName, party.Region = profile.DisplayName, profile.Region
	}
	return party
}

// SuspiciousQuery fields fall back to their defaults when zero, negative or
// NaN.
type SuspiciousQuery struct {
	SinceHours float64
	MinRisk    float64
	Limit      float64
}

type SuspiciousUnit struct {
	BatchID    string  `json:"batchId"`
	Index      int32   `json:"index"`
	MaxRisk    float64 `json:"maxRisk"`
	Scans      int32   `json:"scans"`
	Devices    int32   `json:"devices"`
	LastScanAt string  `json:"lastScanAt"`
}

// ListSuspicious lists units whose scans reached minRisk (default: the high
// threshold) within the window, highest risk first.
func (s *Service) ListSuspicious(ctx context.Context, q SuspiciousQuery) ([]SuspiciousUnit, error) {
	hours := clampInt(positiveOrNaN(q.SinceHours), 24*7, 1, 24*365)
	limit := clampInt(positiveOrNaN(q.Limit), 50, 1, 500)
	minRisk := s.opts.Risk.HighThreshold
	if q.MinRisk > 0 && q.MinRisk <= 1 {
		minRisk = q.MinRisk
	}
	rows, err := s.store.ListSuspiciousUnits(ctx, db.ListSuspiciousUnitsParams{
		Since:   s.now().UTC().Add(-time.Duration(hours) * time.Hour),
		MinRisk: minRisk,
		MaxRows: int32(limit),
	})
	if err != nil {
		return nil, err
	}
	out := make([]SuspiciousUnit, 0, len(rows))
	for _, r := range rows {
		out = append(out, SuspiciousUnit{
			BatchID: formatID(r.BatchID), Index: r.Index, MaxRisk: r.MaxRisk,
			Scans: r.Scans, Devices: r.Devices, LastScanAt: jsonx.ISOTime(r.LastScanAt),
		})
	}
	return out, nil
}
