package risk

import (
	"testing"
	"time"
)

var t0 = time.Date(2026, 10, 1, 9, 0, 0, 0, time.UTC)

func at(minutes int) time.Time { return t0.Add(time.Duration(minutes) * time.Minute) }

func TestGenuineLifecycleIsLowRisk(t *testing.T) {
	consumed := at(30)
	scans := []Scan{
		{Device: "pharmacist", Region: "tehran", At: at(0)},
		{Device: "buyer", Region: "tehran", At: at(10)},
		{Device: "buyer", Region: "tehran", At: at(20)},
		{Device: "buyer", Region: "tehran", At: at(90)},
	}
	s := FromHistory(scans, &consumed, "tehran")
	if s.DevicesBeforeConsume != 2 || s.ScansAfterConsume != 1 || s.NewDevicesAfterConsume != 0 || s.ForeignRegions != 0 {
		t.Fatalf("signals = %+v", s)
	}
	a := Assess(s, DefaultConfig())
	if a.Score != 0 || a.Level != LevelLow || len(a.Reasons) != 0 {
		t.Fatalf("assessment = %+v", a)
	}
}

func TestCloneAfterConsumptionIsHighRisk(t *testing.T) {
	consumed := at(30)
	scans := []Scan{
		{Device: "buyer", Region: "tehran", At: at(10)},
		{Device: "clone-1", Region: "mashhad", At: at(60)},
		{Device: "clone-2", Region: "shiraz", At: at(70)},
	}
	a := Assess(FromHistory(scans, &consumed, "tehran"), DefaultConfig())
	if a.Level != LevelHigh {
		t.Fatalf("assessment = %+v", a)
	}
	codes := map[string]bool{}
	for _, r := range a.Reasons {
		codes[r.Code] = true
	}
	if !codes[ReasonScanAfterConsume] || !codes[ReasonForeignRegion] {
		t.Fatalf("reasons = %+v", a.Reasons)
	}
}

func TestManyDevicesBeforeSaleRaisesRisk(t *testing.T) {
	var scans []Scan
	for i, d := range []string{"a", "b", "c", "d", "e", "f"} {
		scans = append(scans, Scan{Device: d, At: at(i)})
	}
	cfg := DefaultConfig()
	a := Assess(FromHistory(scans, nil, ""), cfg)
	// 3 devices over the threshold: 1 - 0.75^3 = 0.578.
	if a.Score != 0.578 || a.Level != LevelMedium || a.Reasons[0].Code != ReasonManyDevices {
		t.Fatalf("assessment = %+v", a)
	}
	if Assess(FromHistory(scans[:3], nil, ""), cfg).Score != 0 {
		t.Fatal("devices within the threshold must not raise risk")
	}
}

func TestRegionSpreadWithoutKnownOwnerRegion(t *testing.T) {
	scans := []Scan{
		{Device: "a", Region: "tehran", At: at(0)},
		{Device: "a", Region: "tabriz", At: at(1)},
		{Device: "b", At: at(2)},
	}
	s := FromHistory(scans, nil, "")
	if s.DistinctRegions != 2 || s.OwnerRegionKnown {
		t.Fatalf("signals = %+v", s)
	}
	a := Assess(s, DefaultConfig())
	if len(a.Reasons) != 1 || a.Reasons[0].Code != ReasonRegionSpread || a.Score != 0.3 {
		t.Fatalf("assessment = %+v", a)
	}
}

func TestScoreIsMonotoneAndBounded(t *testing.T) {
	cfg := DefaultConfig()
	prev := -1.0
	for devices := 0; devices <= 50; devices++ {
		s := Signals{DevicesBeforeConsume: devices, Consumed: true, NewDevicesAfterConsume: devices, OwnerRegionKnown: true, ForeignRegions: devices}
		score := Assess(s, cfg).Score
		if score < prev || score < 0 || score >= 1 {
			t.Fatalf("devices=%d score=%v prev=%v", devices, score, prev)
		}
		prev = score
	}
}

func TestScanAtConsumptionInstantCountsAsBefore(t *testing.T) {
	consumed := at(5)
	s := FromHistory([]Scan{{Device: "buyer", At: consumed}}, &consumed, "")
	if s.DevicesBeforeConsume != 1 || s.ScansAfterConsume != 0 {
		t.Fatalf("signals = %+v", s)
	}
}
