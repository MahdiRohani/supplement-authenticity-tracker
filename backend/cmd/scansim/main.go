// Command scansim evaluates clone detection on synthetic scan histories.
//
// It simulates genuine units and units whose public label was copied onto K
// counterfeit boxes, replays every unit's scans in time order through the
// production risk engine (internal/risk), and reports precision, recall, F1
// and false-positive rate per decision threshold, against naive baselines
// and with each rule ablated. Runs are deterministic for a given -seed.
//
//	go run ./cmd/scansim -seed 7 -units 5000 -out bench/results
package main

import (
	"encoding/csv"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math"
	"math/rand/v2"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strconv"
	"time"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/risk"
)

// Model holds every behavioural assumption of the simulation; it is written
// next to the results so they can be reproduced and criticised.
type Model struct {
	Seed        uint64  `json:"seed"`
	Units       int     `json:"unitsPerClass"`
	Regions     int     `json:"regions"`
	RegionZipfS float64 `json:"regionZipfExponent"`
	HorizonDays float64 `json:"horizonDays"`

	PharmacistScanP    float64 `json:"pharmacistScanProbability"`
	ShelfScansMean     float64 `json:"shelfScansMean"`
	BuyerScanP         float64 `json:"buyerScanAtPurchaseProbability"`
	ConsumeP           float64 `json:"consumeProbability"`
	RescansMean        float64 `json:"buyerRescansMean"`
	FamilyDeviceP      float64 `json:"familyDeviceProbability"`
	TravelP            float64 `json:"travelProbability"`
	CloneCounts        []int   `json:"cloneCounts"`
	CloneLocalP        float64 `json:"cloneLocalRegionProbability"`
	CloneBuyerRescans  float64 `json:"cloneBuyerRescansMean"`
	OwnerRegionKnownP  float64 `json:"ownerRegionKnownProbability"`
	ScanRegionPresentP float64 `json:"scanRegionPresentProbability"`
}

func defaultModel() Model {
	return Model{
		Seed: 7, Units: 5000, Regions: 31, RegionZipfS: 1.0, HorizonDays: 90,
		PharmacistScanP: 0.5, ShelfScansMean: 0.3, BuyerScanP: 0.8, ConsumeP: 0.7,
		RescansMean: 0.6, FamilyDeviceP: 0.15, TravelP: 0.05,
		CloneCounts: []int{1, 2, 3, 5, 10, 20}, CloneLocalP: 0.3, CloneBuyerRescans: 0.3,
		OwnerRegionKnownP: 0.9, ScanRegionPresentP: 0.85,
	}
}

type scan struct {
	risk.Scan
	clone bool
}

type unit struct {
	cloned      bool
	clones      int
	ownerRegion string
	consumedAt  *time.Time
	scans       []scan
}

type sim struct {
	m       Model
	rng     *rand.Rand
	regions []string
	weights []float64
	epoch   time.Time
}

func newSim(m Model) *sim {
	s := &sim{m: m, rng: rand.New(rand.NewPCG(m.Seed, m.Seed^0x9e3779b97f4a7c15)), epoch: time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)}
	total := 0.0
	for i := range m.Regions {
		s.regions = append(s.regions, fmt.Sprintf("region-%02d", i))
		w := 1 / math.Pow(float64(i+1), m.RegionZipfS)
		s.weights = append(s.weights, w)
		total += w
	}
	for i := range s.weights {
		s.weights[i] /= total
	}
	return s
}

func (s *sim) region() string {
	x, acc := s.rng.Float64(), 0.0
	for i, w := range s.weights {
		if acc += w; x < acc {
			return s.regions[i]
		}
	}
	return s.regions[len(s.regions)-1]
}

func (s *sim) poisson(mean float64) int {
	l, k, p := math.Exp(-mean), 0, 1.0
	for {
		if p *= s.rng.Float64(); p <= l {
			return k
		}
		k++
	}
}

func (s *sim) at(days float64) time.Time {
	return s.epoch.Add(time.Duration(days * float64(24*time.Hour)))
}

func (s *sim) uniform(lo, hi float64) float64 { return lo + s.rng.Float64()*(hi-lo) }

func (s *sim) chance(p float64) bool { return s.rng.Float64() < p }

func (s *sim) add(u *unit, device, region string, day float64, clone bool) {
	if !s.chance(s.m.ScanRegionPresentP) {
		region = ""
	}
	u.scans = append(u.scans, scan{Scan: risk.Scan{Device: device, Region: region, At: s.at(day)}, clone: clone})
}

// genuine simulates the life of a real unit: shelf scans at the pharmacy,
// the buyer's scan at purchase, consumption, and re-scans afterwards.
func (s *sim) genuine(id int) unit {
	owner := s.region()
	u := unit{ownerRegion: owner}
	if !s.chance(s.m.OwnerRegionKnownP) {
		u.ownerRegion = ""
	}
	shelf := s.uniform(0, s.m.HorizonDays/3)
	sale := shelf + s.uniform(1, s.m.HorizonDays/3)
	if s.chance(s.m.PharmacistScanP) {
		s.add(&u, fmt.Sprintf("pharm-%d", id), owner, shelf, false)
	}
	for i := range s.poisson(s.m.ShelfScansMean) {
		s.add(&u, fmt.Sprintf("shelf-%d-%d", id, i), owner, s.uniform(shelf, sale), false)
	}
	buyer := fmt.Sprintf("buyer-%d", id)
	if s.chance(s.m.BuyerScanP) {
		s.add(&u, buyer, owner, sale, false)
	}
	if !s.chance(s.m.ConsumeP) {
		return u
	}
	consumed := s.at(sale + 0.01)
	u.consumedAt = &consumed
	home := owner
	if s.chance(s.m.TravelP) {
		home = s.region()
	}
	for range s.poisson(s.m.RescansMean) {
		s.add(&u, buyer, home, s.uniform(sale+0.02, s.m.HorizonDays), false)
	}
	if s.chance(s.m.FamilyDeviceP) {
		s.add(&u, fmt.Sprintf("family-%d", id), home, s.uniform(sale+0.02, s.m.HorizonDays), false)
	}
	return u
}

// cloned is a genuine unit whose public QR was copied onto k fake boxes,
// each sold to a different buyer who scans it at least once.
func (s *sim) cloned(id, k int) unit {
	u := s.genuine(id)
	u.cloned, u.clones = true, k
	owner := u.ownerRegion
	for c := range k {
		region := s.region()
		if owner != "" && s.chance(s.m.CloneLocalP) {
			region = owner
		}
		device := fmt.Sprintf("victim-%d-%d", id, c)
		day := s.uniform(0, s.m.HorizonDays)
		for r := range 1 + s.poisson(s.m.CloneBuyerRescans) {
			s.add(&u, device, region, day+float64(r)*s.uniform(0.1, 5), true)
		}
	}
	return u
}

func (s *sim) population() []unit {
	units := make([]unit, 0, 2*s.m.Units)
	for i := range s.m.Units {
		units = append(units, s.genuine(i))
	}
	for i := range s.m.Units {
		k := s.m.CloneCounts[i%len(s.m.CloneCounts)]
		units = append(units, s.cloned(s.m.Units+i, k))
	}
	for i := range units {
		slices.SortStableFunc(units[i].scans, func(a, b scan) int { return a.At.Compare(b.At) })
	}
	return units
}

// replay scores every scan with the history up to it, as the API does, and
// returns the peak score and how many clone scans happened up to the first
// scan reaching each threshold.
type outcome struct {
	peak float64
	// firstAt[i] is the number of clone scans seen when the score first
	// reached thresholds[i]; -1 when it never did.
	firstAt []int
}

func replay(u unit, cfg risk.Config, thresholds []float64) outcome {
	out := outcome{firstAt: make([]int, len(thresholds))}
	for i := range out.firstAt {
		out.firstAt[i] = -1
	}
	history := make([]risk.Scan, 0, len(u.scans))
	clones := 0
	for _, sc := range u.scans {
		history = append(history, sc.Scan)
		if sc.clone {
			clones++
		}
		var consumed *time.Time
		if u.consumedAt != nil && !sc.At.Before(*u.consumedAt) {
			consumed = u.consumedAt
		}
		score := risk.Assess(risk.FromHistory(history, consumed, u.ownerRegion), cfg).Score
		out.peak = math.Max(out.peak, score)
		for i, th := range thresholds {
			if out.firstAt[i] < 0 && score >= th {
				out.firstAt[i] = clones
			}
		}
	}
	return out
}

type confusion struct{ tp, fp, tn, fn int }

func (c *confusion) add(cloned, flagged bool) {
	switch {
	case cloned && flagged:
		c.tp++
	case cloned:
		c.fn++
	case flagged:
		c.fp++
	default:
		c.tn++
	}
}

func ratio(a, b int) float64 {
	if b == 0 {
		return 0
	}
	return float64(a) / float64(b)
}

func (c confusion) precision() float64 { return ratio(c.tp, c.tp+c.fp) }
func (c confusion) recall() float64    { return ratio(c.tp, c.tp+c.fn) }
func (c confusion) fpr() float64       { return ratio(c.fp, c.fp+c.tn) }
func (c confusion) f1() float64 {
	p, r := c.precision(), c.recall()
	if p+r == 0 {
		return 0
	}
	return 2 * p * r / (p + r)
}

func (c confusion) row(prefix ...string) []string {
	return append(prefix,
		strconv.Itoa(c.tp), strconv.Itoa(c.fp), strconv.Itoa(c.tn), strconv.Itoa(c.fn),
		f3(c.precision()), f3(c.recall()), f3(c.f1()), f3(c.fpr()))
}

var metricHeader = []string{"tp", "fp", "tn", "fn", "precision", "recall", "f1", "fpr"}

func f3(v float64) string { return strconv.FormatFloat(v, 'f', 4, 64) }

func writeCSV(path string, header []string, rows [][]string) {
	f, err := os.Create(path)
	if err != nil {
		log.Fatal(err)
	}
	w := csv.NewWriter(f)
	_ = w.Write(header)
	_ = w.WriteAll(rows)
	if err := f.Close(); err != nil {
		log.Fatal(err)
	}
}

type detector struct {
	name, params string
	flag         func(u unit) bool
}

// detectorsFor lists the proposed detector, naive baselines and ablations.
// The proposed detector and ablations score online, at each scan, as the
// API does; the baselines see the whole history at the end of the horizon,
// which favours them.
func detectorsFor(cfg risk.Config) []detector {
	online := func(c risk.Config) func(unit) bool {
		return func(u unit) bool { return replay(u, c, []float64{c.HighThreshold}).firstAt[0] >= 0 }
	}
	final := func(u unit) risk.Signals {
		hist := make([]risk.Scan, len(u.scans))
		for i, sc := range u.scans {
			hist[i] = sc.Scan
		}
		return risk.FromHistory(hist, u.consumedAt, u.ownerRegion)
	}
	ablate := func(name string, mutate func(*risk.Config)) detector {
		c := cfg
		mutate(&c)
		return detector{"ablation", name, online(c)}
	}
	return []detector{
		{"proposed", fmt.Sprintf("noisy-or, threshold=%.2f", cfg.HighThreshold), online(cfg)},
		{"status-only", "any scan after consumption", func(u unit) bool { return final(u).ScansAfterConsume > 0 }},
		{"scan-count", "total scans > 3", func(u unit) bool { return final(u).TotalScans > 3 }},
		{"scan-count", "total scans > 5", func(u unit) bool { return final(u).TotalScans > 5 }},
		{"device-count", "distinct devices > 3", func(u unit) bool {
			devices := map[string]bool{}
			for _, sc := range u.scans {
				devices[sc.Device] = true
			}
			return len(devices) > 3
		}},
		ablate("without many_devices", func(c *risk.Config) { c.DeviceWeight = 0 }),
		ablate("without scanned_after_consumption", func(c *risk.Config) { c.PostConsumeWeight = 0 }),
		ablate("without region rules", func(c *risk.Config) { c.RegionWeight = 0 }),
	}
}

func evaluate(units []unit, det detector) confusion {
	var c confusion
	for _, u := range units {
		c.add(u.cloned, det.flag(u))
	}
	return c
}

func meanStd(vals []float64) (float64, float64) {
	if len(vals) == 0 {
		return 0, 0
	}
	sum := 0.0
	for _, v := range vals {
		sum += v
	}
	mean := sum / float64(len(vals))
	if len(vals) == 1 {
		return mean, 0
	}
	ss := 0.0
	for _, v := range vals {
		ss += (v - mean) * (v - mean)
	}
	return mean, math.Sqrt(ss / float64(len(vals)-1))
}

func main() {
	m := defaultModel()
	flag.Uint64Var(&m.Seed, "seed", m.Seed, "random seed")
	flag.IntVar(&m.Units, "units", m.Units, "units per class (genuine and cloned)")
	flag.Float64Var(&m.CloneLocalP, "local", m.CloneLocalP, "probability a counterfeit is sold in the custodian's region")
	flag.Float64Var(&m.FamilyDeviceP, "family", m.FamilyDeviceP, "probability a second household device scans a genuine unit")
	runs := flag.Int("runs", 10, "independent seeds (seed, seed+1, ...) for mean/std of the detector comparison")
	outDir := flag.String("out", "bench/results", "output directory")
	flag.Parse()

	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		log.Fatal(err)
	}
	units := newSim(m).population()
	cfg := risk.DefaultConfig()

	thresholds := []float64{}
	for th := 0.05; th < 0.96; th += 0.05 {
		thresholds = append(thresholds, math.Round(th*100)/100)
	}
	highIdx := slices.Index(thresholds, cfg.HighThreshold)

	outcomes := make([]outcome, len(units))
	for i, u := range units {
		outcomes[i] = replay(u, cfg, thresholds)
	}

	var thresholdRows [][]string
	for i, th := range thresholds {
		var c confusion
		for j, u := range units {
			c.add(u.cloned, outcomes[j].firstAt[i] >= 0)
		}
		thresholdRows = append(thresholdRows, c.row(f3(th)))
	}
	writeCSV(filepath.Join(*outDir, "scansim_thresholds.csv"), append([]string{"threshold"}, metricHeader...), thresholdRows)

	detectors := detectorsFor(cfg)
	var baselineRows [][]string
	for _, det := range detectors {
		baselineRows = append(baselineRows, evaluate(units, det).row(det.name, det.params))
	}
	writeCSV(filepath.Join(*outDir, "scansim_baselines.csv"), append([]string{"detector", "params"}, metricHeader...), baselineRows)

	// The same comparison over independent populations, for error bars.
	perRun := make([][]confusion, len(detectors))
	var runRows [][]string
	for r := range max(1, *runs) {
		rm := m
		rm.Seed = m.Seed + uint64(r)
		pop := units
		if r > 0 {
			pop = newSim(rm).population()
		}
		for d, det := range detectors {
			c := evaluate(pop, det)
			perRun[d] = append(perRun[d], c)
			runRows = append(runRows, c.row(strconv.FormatUint(rm.Seed, 10), det.name, det.params))
		}
	}
	writeCSV(filepath.Join(*outDir, "scansim_runs.csv"), append([]string{"seed", "detector", "params"}, metricHeader...), runRows)
	var summaryRows [][]string
	var summary []string
	for d, det := range detectors {
		stats := func(metric func(confusion) float64) (float64, float64) {
			vals := make([]float64, len(perRun[d]))
			for i, c := range perRun[d] {
				vals[i] = metric(c)
			}
			return meanStd(vals)
		}
		pm, ps := stats(confusion.precision)
		rmu, rs := stats(confusion.recall)
		fm, fs := stats(confusion.f1)
		xm, xs := stats(confusion.fpr)
		summaryRows = append(summaryRows, []string{det.name, det.params, strconv.Itoa(len(perRun[d])),
			f3(pm), f3(ps), f3(rmu), f3(rs), f3(fm), f3(fs), f3(xm), f3(xs)})
		summary = append(summary, fmt.Sprintf("%-13s %-36s P=%.3f±%.3f R=%.3f±%.3f F1=%.3f±%.3f FPR=%.4f±%.4f",
			det.name, det.params, pm, ps, rmu, rs, fm, fs, xm, xs))
	}
	writeCSV(filepath.Join(*outDir, "scansim_summary.csv"), []string{"detector", "params", "runs",
		"precision_mean", "precision_std", "recall_mean", "recall_std", "f1_mean", "f1_std", "fpr_mean", "fpr_std"}, summaryRows)

	// Recall and detection delay by number of copies.
	byClones := map[int][]int{}
	detected := map[int]int{}
	totals := map[int]int{}
	for j, u := range units {
		if !u.cloned {
			continue
		}
		totals[u.clones]++
		if n := outcomes[j].firstAt[highIdx]; n >= 0 {
			detected[u.clones]++
			byClones[u.clones] = append(byClones[u.clones], n)
		}
	}
	ks := make([]int, 0, len(totals))
	for k := range totals {
		ks = append(ks, k)
	}
	sort.Ints(ks)
	var cloneRows [][]string
	for _, k := range ks {
		delays := byClones[k]
		sort.Ints(delays)
		mean, median := 0.0, 0.0
		if len(delays) > 0 {
			sum := 0
			for _, d := range delays {
				sum += d
			}
			mean, median = float64(sum)/float64(len(delays)), float64(delays[len(delays)/2])
		}
		cloneRows = append(cloneRows, []string{
			strconv.Itoa(k), strconv.Itoa(totals[k]), strconv.Itoa(detected[k]),
			f3(ratio(detected[k], totals[k])), f3(mean), f3(median),
		})
	}
	writeCSV(filepath.Join(*outDir, "scansim_by_clones.csv"),
		[]string{"clones", "units", "detected", "recall", "mean_clone_scans_to_flag", "median_clone_scans_to_flag"}, cloneRows)

	modelJSON, _ := json.MarshalIndent(struct {
		Model Model       `json:"model"`
		Risk  risk.Config `json:"risk"`
	}{m, cfg}, "", "  ")
	if err := os.WriteFile(filepath.Join(*outDir, "scansim_config.json"), append(modelJSON, '\n'), 0o644); err != nil {
		log.Fatal(err)
	}

	fmt.Printf("scansim: %d genuine + %d cloned units, seeds %d..%d\n", m.Units, m.Units, m.Seed, m.Seed+uint64(max(1, *runs))-1)
	for _, line := range summary {
		fmt.Println(line)
	}
	fmt.Println("recall by number of copies (threshold", cfg.HighThreshold, "):")
	for _, r := range cloneRows {
		fmt.Printf("  k=%-3s recall=%s  median clone scans to flag=%s\n", r[0], r[3], r[5])
	}
	fmt.Println("wrote", *outDir)
}
