// Command loadgen measures read-path latency and throughput of a running API.
//
// For each concurrency level it issues requests for a fixed duration against
// one endpoint and reports throughput and latency percentiles. Verify
// requests record a scan and score clone risk, so they exercise the full
// public path; -fresh sends Cache-Control: no-cache to bypass the batch
// snapshot cache (honoured outside production only).
//
// The API's per-IP verify limit must be raised for a meaningful run, e.g.
// VERIFY_RATE_LIMIT=100000000. With -setup, a batch is registered first
// (API_WRITE_KEY via -key) and moved to a pharmacy.
//
// -endpoint consume measures the end-to-end gasless consumption: each request
// signs a ConsumeAuthorization with a fresh unit's key and posts it to
// /v2/consume, which relays the transaction and waits for its receipt. Every
// unit is consumed once, so it needs -setup, enough -units for all levels,
// and a raised CONSUME_RATE_LIMIT.
//
//	go run ./cmd/loadgen -url http://127.0.0.1:3000 -setup -levels 1,8,32,64 -duration 10s
//	go run ./cmd/loadgen -url http://127.0.0.1:3000 -setup -endpoint consume -units 200 -levels 1,4 -duration 20s
package main

import (
	"bytes"
	"context"
	"encoding/csv"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"math/rand/v2"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/crypto"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/protocol"
)

type options struct {
	baseURL  string
	key      string
	endpoint string
	chainID  int64
	registry string
	batchID  string
	secrets  []string
	nextUnit *atomic.Int64
	units    int
	levels   []int
	duration time.Duration
	warmup   time.Duration
	fresh    bool
	devices  int
	out      string
	label    string
}

type result struct {
	latencies []time.Duration
	codes     map[int]int
	errors    int
}

func main() {
	var o options
	var levels string
	setup := flag.Bool("setup", false, "register a fresh batch and ship it via a distributor to a pharmacy before measuring")
	distributor := flag.String("distributor", "0x70997970c51812dc3a010c7d01b50e0d17dc79c8", "distributor wallet for -setup (must be a relayer key)")
	pharmacy := flag.String("pharmacy", "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc", "pharmacy wallet for -setup")
	flag.StringVar(&o.baseURL, "url", "http://127.0.0.1:3000", "API base URL")
	flag.StringVar(&o.key, "key", os.Getenv("API_WRITE_KEY"), "write API key for -setup")
	flag.StringVar(&o.endpoint, "endpoint", "verify", "verify | proof | batch | consume")
	flag.StringVar(&o.batchID, "batch", "", "batch id to read (required without -setup)")
	flag.IntVar(&o.units, "units", 100, "units in the batch (indexes are drawn uniformly)")
	flag.StringVar(&levels, "levels", "1,8,32,64", "comma-separated concurrency levels")
	flag.DurationVar(&o.duration, "duration", 10*time.Second, "measurement time per level")
	flag.DurationVar(&o.warmup, "warmup", 2*time.Second, "unmeasured warm-up per level")
	flag.BoolVar(&o.fresh, "fresh", false, "bypass the batch snapshot cache (Cache-Control: no-cache)")
	flag.IntVar(&o.devices, "devices", 1000, "distinct simulated scanning devices")
	flag.StringVar(&o.out, "out", "bench/results", "output directory")
	flag.StringVar(&o.label, "label", "", "free-form label stored with each row (e.g. hardware)")
	flag.Parse()

	for _, part := range strings.Split(levels, ",") {
		n, err := strconv.Atoi(strings.TrimSpace(part))
		if err != nil || n < 1 {
			log.Fatalf("bad concurrency level %q", part)
		}
		o.levels = append(o.levels, n)
	}
	o.baseURL = strings.TrimRight(o.baseURL, "/")
	client := &http.Client{Timeout: 30 * time.Second, Transport: &http.Transport{MaxIdleConnsPerHost: 1024, MaxConnsPerHost: 0}}

	var err error
	if o.chainID, o.registry, err = activeChain(client, o.baseURL); err != nil {
		log.Fatalf("GET /v2/chains: %v", err)
	}
	consume := o.endpoint == "consume"
	if consume && !*setup {
		log.Fatal("-endpoint consume needs -setup (it consumes fresh units whose keys it registered)")
	}
	if *setup {
		if o.batchID, o.secrets, err = setupBatch(client, o, *distributor, *pharmacy); err != nil {
			log.Fatalf("setup: %v", err)
		}
		log.Printf("registered batch %s with %d units", o.batchID, o.units)
	}
	if o.batchID == "" {
		log.Fatal("-batch or -setup is required")
	}
	o.nextUnit = new(atomic.Int64)

	if err := os.MkdirAll(o.out, 0o755); err != nil {
		log.Fatal(err)
	}
	path := filepath.Join(o.out, "loadgen.csv")
	newFile := true
	if _, err := os.Stat(path); err == nil {
		newFile = false
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		log.Fatal(err)
	}
	defer f.Close()
	w := csv.NewWriter(f)
	if newFile {
		_ = w.Write([]string{"timestamp", "label", "endpoint", "cache", "concurrency", "duration_s", "requests", "ok", "throttled", "errors",
			"throughput_rps", "p50_ms", "p95_ms", "p99_ms", "max_ms", "mean_ms"})
	}
	cache := "snapshot-cache"
	if o.fresh {
		cache = "no-cache"
	}
	if consume {
		cache = "n/a"
	}
	fmt.Printf("%-8s %-15s %5s %9s %9s %8s %8s %8s %8s\n", "endpoint", "cache", "conc", "requests", "rps", "p50ms", "p95ms", "p99ms", "errors")
	for _, level := range o.levels {
		var r result
		if consume {
			r = runConsume(client, o, level, o.duration)
		} else {
			run(client, o, level, o.warmup)
			r = run(client, o, level, o.duration)
		}
		ok, throttled := r.codes[200]+r.codes[201], r.codes[429]
		lat := slices.Clone(r.latencies)
		slices.Sort(lat)
		var sum time.Duration
		for _, d := range lat {
			sum += d
		}
		mean := time.Duration(0)
		if len(lat) > 0 {
			mean = sum / time.Duration(len(lat))
		}
		rps := float64(len(lat)) / o.duration.Seconds()
		errs := r.errors + len(lat) - ok - throttled
		_ = w.Write([]string{
			time.Now().UTC().Format(time.RFC3339), o.label, o.endpoint, cache, strconv.Itoa(level), ftoa(o.duration.Seconds()),
			strconv.Itoa(len(lat)), strconv.Itoa(ok), strconv.Itoa(throttled), strconv.Itoa(errs), ftoa(rps),
			ms(pct(lat, 0.50)), ms(pct(lat, 0.95)), ms(pct(lat, 0.99)), ms(pct(lat, 1)), ms(mean),
		})
		w.Flush()
		fmt.Printf("%-8s %-15s %5d %9d %9.1f %8s %8s %8s %8d\n", o.endpoint, cache, level, len(lat), rps,
			ms(pct(lat, 0.50)), ms(pct(lat, 0.95)), ms(pct(lat, 0.99)), errs)
		if throttled > 0 {
			log.Printf("warning: %d requests were rate limited; raise VERIFY_RATE_LIMIT / CONSUME_RATE_LIMIT on the API", throttled)
		}
		if consume && int(o.nextUnit.Load()) >= len(o.secrets) {
			log.Printf("warning: all %d units are consumed; later levels were cut short (raise -units)", len(o.secrets))
		}
	}
	if err := w.Error(); err != nil {
		log.Fatal(err)
	}
	fmt.Println("appended to", path)
}

func run(client *http.Client, o options, workers int, d time.Duration) result {
	ctx, cancel := context.WithTimeout(context.Background(), d)
	defer cancel()
	results := make([]result, workers)
	var wg sync.WaitGroup
	for i := range workers {
		wg.Go(func() {
			rng := rand.New(rand.NewPCG(uint64(i)+1, uint64(time.Now().UnixNano())))
			res := result{codes: map[int]int{}}
			for ctx.Err() == nil {
				req, err := http.NewRequestWithContext(ctx, http.MethodGet, o.target(rng), nil)
				if err != nil {
					log.Fatal(err)
				}
				req.Header.Set("X-Device-Id", fmt.Sprintf("loadgen-%d", rng.IntN(max(1, o.devices))))
				req.Header.Set("X-Scan-Region", fmt.Sprintf("region-%02d", rng.IntN(31)))
				if o.fresh {
					req.Header.Set("Cache-Control", "no-cache")
				}
				start := time.Now()
				resp, err := client.Do(req)
				if err != nil {
					if ctx.Err() == nil {
						res.errors++
					}
					continue
				}
				_, _ = io.Copy(io.Discard, resp.Body)
				resp.Body.Close()
				if ctx.Err() != nil {
					break
				}
				res.latencies = append(res.latencies, time.Since(start))
				res.codes[resp.StatusCode]++
			}
			results[i] = res
		})
	}
	wg.Wait()
	merged := result{codes: map[int]int{}}
	for _, r := range results {
		merged.latencies = append(merged.latencies, r.latencies...)
		merged.errors += r.errors
		for code, n := range r.codes {
			merged.codes[code] += n
		}
	}
	return merged
}

// runConsume starts no new request after d, but lets in-flight ones finish:
// cancelling a relayed consume would still spend the unit on-chain.
func runConsume(client *http.Client, o options, workers int, d time.Duration) result {
	consumerKey, err := crypto.GenerateKey()
	if err != nil {
		log.Fatal(err)
	}
	consumer := crypto.PubkeyToAddress(consumerKey.PublicKey)
	batchID, ok := new(big.Int).SetString(o.batchID, 10)
	if !ok {
		log.Fatalf("bad batch id %q", o.batchID)
	}
	signer := chain.NewEIP712V2(o.registry)
	end := time.Now().Add(d)
	results := make([]result, workers)
	var wg sync.WaitGroup
	for w := range workers {
		wg.Go(func() {
			res := result{codes: map[int]int{}}
			for time.Now().Before(end) {
				i := int(o.nextUnit.Add(1) - 1)
				if i >= len(o.secrets) {
					break
				}
				start := time.Now()
				label, err := protocol.ParseSecretQR(o.secrets[i])
				if err != nil {
					log.Fatalf("unit %d: %v", i, err)
				}
				// The app uses 10 minutes; an hour tolerates automine pushing block
				// timestamps ahead of the wall clock during long runs.
				deadline := time.Now().Add(time.Hour).Unix()
				sig, err := signer.SignUnitConsume(label.Key, chain.UnitConsumeAuthorization{
					BatchID: batchID, Index: uint32(i), Consumer: consumer, Deadline: big.NewInt(deadline), ChainID: o.chainID,
				})
				if err != nil {
					log.Fatalf("sign unit %d: %v", i, err)
				}
				raw, _ := json.Marshal(map[string]any{
					"chainId": o.chainID, "batchId": o.batchID, "index": i, "consumer": consumer.Hex(),
					"deadline": deadline, "signature": hexutil.Encode(sig),
				})
				resp, err := client.Post(o.baseURL+"/v2/consume", "application/json", bytes.NewReader(raw))
				if err != nil {
					res.errors++
					continue
				}
				body, _ := io.ReadAll(resp.Body)
				resp.Body.Close()
				res.latencies = append(res.latencies, time.Since(start))
				res.codes[resp.StatusCode]++
				if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
					log.Printf("worker %d unit %d: %d %.200s", w, i, resp.StatusCode, body)
				}
			}
			results[w] = res
		})
	}
	wg.Wait()
	merged := result{codes: map[int]int{}}
	for _, r := range results {
		merged.latencies = append(merged.latencies, r.latencies...)
		merged.errors += r.errors
		for code, n := range r.codes {
			merged.codes[code] += n
		}
	}
	return merged
}

func (o options) target(rng *rand.Rand) string {
	index := rng.IntN(max(1, o.units))
	switch o.endpoint {
	case "proof":
		return fmt.Sprintf("%s/v2/batches/%s/units/%d/proof", o.baseURL, o.batchID, index)
	case "batch":
		return fmt.Sprintf("%s/v2/batches/%s", o.baseURL, o.batchID)
	default:
		return fmt.Sprintf("%s/v2/verify/%d/%s/%d", o.baseURL, o.chainID, o.batchID, index)
	}
}

func activeChain(client *http.Client, base string) (int64, string, error) {
	var body struct {
		ActiveChainID int64 `json:"activeChainId"`
		EIP712Domain  struct {
			VerifyingContract string `json:"verifyingContract"`
		} `json:"eip712Domain"`
	}
	if err := doJSON(client, http.MethodGet, base+"/v2/chains", "", nil, &body); err != nil {
		return 0, "", err
	}
	return body.ActiveChainID, body.EIP712Domain.VerifyingContract, nil
}

// setupBatch returns the batch id and every unit's secret QR, by index.
func setupBatch(client *http.Client, o options, distributor, pharmacy string) (string, []string, error) {
	for _, party := range []map[string]any{
		{"address": distributor, "role": "Distributor", "displayName": "Loadgen Distributor", "region": "region-00"},
		{"address": pharmacy, "role": "Pharmacy", "displayName": "Loadgen Pharmacy", "region": "region-00"},
	} {
		if err := doJSON(client, http.MethodPost, o.baseURL+"/v2/roles", o.key, party, nil); err != nil {
			return "", nil, err
		}
	}
	var reg struct {
		BatchID   string `json:"batchId"`
		SegmentID string `json:"segmentId"`
		Units     []struct {
			Index    int    `json:"index"`
			SecretQR string `json:"secretQr"`
		} `json:"units"`
	}
	lot := fmt.Sprintf("LOADGEN-%d", time.Now().UnixNano())
	if err := doJSON(client, http.MethodPost, o.baseURL+"/v2/batches", o.key, map[string]any{"name": "Load test", "lotCode": lot, "size": o.units}, &reg); err != nil {
		return "", nil, err
	}
	for _, to := range []string{distributor, pharmacy} {
		if err := doJSON(client, http.MethodPost, o.baseURL+"/v2/segments/"+reg.SegmentID+"/transfer", o.key, map[string]any{"toAddress": to}, nil); err != nil {
			return "", nil, err
		}
	}
	secrets := make([]string, len(reg.Units))
	for _, u := range reg.Units {
		if u.Index < 0 || u.Index >= len(secrets) {
			return "", nil, fmt.Errorf("unit index %d outside the batch", u.Index)
		}
		secrets[u.Index] = u.SecretQR
	}
	return reg.BatchID, secrets, nil
}

func doJSON(client *http.Client, method, url, key string, in, out any) error {
	var body io.Reader
	if in != nil {
		raw, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, url, body)
	if err != nil {
		return err
	}
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if key != "" {
		req.Header.Set("X-Api-Key", key)
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		return fmt.Errorf("%s %s: %d %s", method, url, resp.StatusCode, raw)
	}
	if out != nil {
		return json.Unmarshal(raw, out)
	}
	return nil
}

func pct(sorted []time.Duration, p float64) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	i := int(p*float64(len(sorted))+0.5) - 1
	return sorted[min(max(i, 0), len(sorted)-1)]
}

func ms(d time.Duration) string {
	return strconv.FormatFloat(float64(d.Microseconds())/1000, 'f', 2, 64)
}

func ftoa(v float64) string { return strconv.FormatFloat(v, 'f', 2, 64) }
