// Package risk scores how likely a public label scan belongs to a cloned
// (copied) label. A counterfeiter can photocopy the public QR of a genuine
// unit but not its hidden one-time key, so clones show up as scan patterns:
// many devices before the genuine unit is consumed, new devices after it was
// consumed, and scans far from the pharmacy that holds the unit.
//
// The score is explainable: each rule yields a weight in [0, 1) from how far
// its signal exceeds what a genuine unit plausibly shows, and the rules are
// combined with a noisy-OR, score = 1 - prod(1 - w_i).
package risk

import (
	"fmt"
	"math"
	"strings"
	"time"
	"unicode"
)

// Scan is one public verification of a unit.
type Scan struct {
	Device string
	Region string
	At     time.Time
}

// Signals are the per-unit aggregates the rules use. The SQL query
// UnitScanSignals computes the same values from the ScanEvent table.
type Signals struct {
	TotalScans int
	// DevicesBeforeConsume counts distinct devices up to the consumption
	// (all devices when the unit is not consumed).
	DevicesBeforeConsume int
	ScansAfterConsume    int
	// NewDevicesAfterConsume counts devices that first scanned the unit
	// after it was consumed; the buyer re-scanning their own box is not new.
	NewDevicesAfterConsume int
	DistinctRegions        int
	// ForeignRegions counts distinct regions other than the custodian's;
	// it is only meaningful when OwnerRegionKnown.
	ForeignRegions   int
	OwnerRegionKnown bool
	Consumed         bool
}

// FromHistory computes Signals from raw scans.
func FromHistory(scans []Scan, consumedAt *time.Time, ownerRegion string) Signals {
	s := Signals{TotalScans: len(scans), OwnerRegionKnown: ownerRegion != "", Consumed: consumedAt != nil}
	before := map[string]bool{}
	for _, sc := range scans {
		if consumedAt == nil || !sc.At.After(*consumedAt) {
			before[sc.Device] = true
		}
	}
	s.DevicesBeforeConsume = len(before)

	newAfter := map[string]bool{}
	regions := map[string]bool{}
	foreign := map[string]bool{}
	for _, sc := range scans {
		if consumedAt != nil && sc.At.After(*consumedAt) {
			s.ScansAfterConsume++
			if !before[sc.Device] {
				newAfter[sc.Device] = true
			}
		}
		if sc.Region != "" {
			regions[sc.Region] = true
			if ownerRegion != "" && sc.Region != ownerRegion {
				foreign[sc.Region] = true
			}
		}
	}
	s.NewDevicesAfterConsume = len(newAfter)
	s.DistinctRegions = len(regions)
	s.ForeignRegions = len(foreign)
	return s
}

type Config struct {
	// DeviceThreshold is how many distinct devices a genuine unit may see
	// before consumption (pharmacist, buyer, family) without raising risk.
	DeviceThreshold int
	// DeviceWeight is the weight of each device beyond DeviceThreshold.
	DeviceWeight float64
	// PostConsumeWeight is the weight of each new device after consumption.
	PostConsumeWeight float64
	// RegionWeight is the weight of each region outside the custodian's.
	RegionWeight float64
	// RegionSpread is how many regions are tolerated when the custodian's
	// region is unknown.
	RegionSpread int
	// MaxRuleWeight caps a single rule so one signal alone never yields 1.
	MaxRuleWeight float64
	// MediumThreshold and HighThreshold map the score to a level.
	MediumThreshold float64
	HighThreshold   float64
}

func DefaultConfig() Config {
	return Config{
		DeviceThreshold:   3,
		DeviceWeight:      0.25,
		PostConsumeWeight: 0.45,
		RegionWeight:      0.3,
		RegionSpread:      1,
		MaxRuleWeight:     0.95,
		MediumThreshold:   0.3,
		HighThreshold:     0.6,
	}
}

const (
	LevelLow    = "low"
	LevelMedium = "medium"
	LevelHigh   = "high"
)

// Rule codes; clients localize messages by code.
const (
	ReasonManyDevices      = "many_devices"
	ReasonScanAfterConsume = "scanned_after_consumption"
	ReasonForeignRegion    = "foreign_region"
	ReasonRegionSpread     = "region_spread"
)

type Reason struct {
	Code    string  `json:"code"`
	Weight  float64 `json:"weight"`
	Message string  `json:"message"`
}

type Assessment struct {
	Score   float64  `json:"score"`
	Level   string   `json:"level"`
	Reasons []Reason `json:"reasons"`
}

func Assess(s Signals, cfg Config) Assessment {
	reasons := []Reason{}
	add := func(code string, count int, unit float64, message string) {
		if count <= 0 || unit <= 0 {
			return
		}
		w := math.Min(cfg.MaxRuleWeight, 1-math.Pow(1-unit, float64(count)))
		reasons = append(reasons, Reason{Code: code, Weight: round(w), Message: message})
	}

	if excess := s.DevicesBeforeConsume - cfg.DeviceThreshold; excess > 0 {
		add(ReasonManyDevices, excess, cfg.DeviceWeight,
			fmt.Sprintf("Scanned by %d different devices before being sold (expected at most %d)", s.DevicesBeforeConsume, cfg.DeviceThreshold))
	}
	if s.Consumed && s.NewDevicesAfterConsume > 0 {
		add(ReasonScanAfterConsume, s.NewDevicesAfterConsume, cfg.PostConsumeWeight,
			fmt.Sprintf("Scanned by %d new device(s) after this unit was already used", s.NewDevicesAfterConsume))
	}
	if s.OwnerRegionKnown {
		add(ReasonForeignRegion, s.ForeignRegions, cfg.RegionWeight,
			fmt.Sprintf("Scanned in %d region(s) other than the selling pharmacy's", s.ForeignRegions))
	} else if spread := s.DistinctRegions - cfg.RegionSpread; spread > 0 {
		add(ReasonRegionSpread, spread, cfg.RegionWeight,
			fmt.Sprintf("Scanned in %d different regions", s.DistinctRegions))
	}

	keep := 1.0
	for _, r := range reasons {
		keep *= 1 - r.Weight
	}
	score := math.Min(round(1-keep), maxScore)
	return Assessment{Score: score, Level: cfg.Level(score), Reasons: reasons}
}

func (c Config) Level(score float64) string {
	switch {
	case score >= c.HighThreshold:
		return LevelHigh
	case score >= c.MediumThreshold:
		return LevelMedium
	default:
		return LevelLow
	}
}

// NormalizeRegion folds a coarse region name (city or province) into the
// comparable key used for both scans and custodian profiles; nil when empty.
func NormalizeRegion(raw string) *string {
	region := strings.Map(func(r rune) rune {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r) || r == '-':
			return r
		case unicode.IsSpace(r) || r == '_':
			return '-'
		default:
			return -1
		}
	}, strings.ToLower(strings.TrimSpace(raw)))
	if region == "" {
		return nil
	}
	if r := []rune(region); len(r) > 64 {
		region = string(r[:64])
	}
	return &region
}

// maxScore keeps rounding from presenting scan evidence as certainty.
const maxScore = 0.999

func round(f float64) float64 { return math.Round(f*1000) / 1000 }
