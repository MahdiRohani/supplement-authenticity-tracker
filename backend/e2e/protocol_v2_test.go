//go:build e2e

// Package e2e drives a running API (E2E_API_URL, default
// http://127.0.0.1:3000) backed by a Hardhat node with SupplementRegistryV2
// deployed, over HTTP only. Run: go test -tags e2e ./e2e/
package e2e

import (
	"bytes"
	"crypto/ecdsa"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/crypto"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/protocol"
)

// Hardhat accounts #1 and #2; their keys must be in RELAYER_KEYS_JSON so the
// API can sign their custody transfers.
const (
	distributor = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"
	pharmacy    = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc"
)

var baseURL = func() string {
	if u := os.Getenv("E2E_API_URL"); u != "" {
		return strings.TrimRight(u, "/")
	}
	return "http://127.0.0.1:3000"
}()

type response struct {
	status int
	body   []byte
	header http.Header
}

func call(t *testing.T, method, path string, body any, headers ...string) response {
	t.Helper()
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, baseURL+path, reader)
	if err != nil {
		t.Fatal(err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if key := os.Getenv("E2E_API_KEY"); key != "" {
		req.Header.Set("X-Api-Key", key)
	}
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	resp, err := (&http.Client{Timeout: 2 * time.Minute}).Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, path, err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	return response{status: resp.StatusCode, body: raw, header: resp.Header}
}

func expect[T any](t *testing.T, r response, status int) T {
	t.Helper()
	if r.status != status {
		t.Fatalf("status %d, want %d: %s", r.status, status, r.body)
	}
	var out T
	if err := json.Unmarshal(r.body, &out); err != nil {
		t.Fatalf("decode %s: %v", r.body, err)
	}
	return out
}

type unitCred struct {
	Index    uint32 `json:"index"`
	UnitKey  string `json:"unitKey"`
	PublicQR string `json:"publicQr"`
	SecretQR string `json:"secretQr"`
}

type verifyResult struct {
	Authenticity string `json:"authenticity"`
	Consumed     bool   `json:"consumed"`
	Recalled     bool   `json:"recalled"`
	Custodian    *struct {
		Address     string  `json:"address"`
		DisplayName *string `json:"displayName"`
		Region      *string `json:"region"`
	} `json:"custodian"`
	Risk struct {
		Score   float64 `json:"score"`
		Level   string  `json:"level"`
		Reasons []struct {
			Code string `json:"code"`
		} `json:"reasons"`
	} `json:"risk"`
	Evidence *struct {
		MerkleRoot string   `json:"merkleRoot"`
		Leaf       string   `json:"leaf"`
		Proof      []string `json:"proof"`
	} `json:"evidence"`
}

func TestProtocolV2Lifecycle(t *testing.T) {
	chains := expect[struct {
		ActiveChainID   int64  `json:"activeChainId"`
		RegistryAddress string `json:"registryAddress"`
		EIP712Domain    struct {
			Version           string `json:"version"`
			VerifyingContract string `json:"verifyingContract"`
		} `json:"eip712Domain"`
	}](t, call(t, "GET", "/v2/chains", nil), 200)
	if chains.RegistryAddress == "" || chains.EIP712Domain.Version != "2" {
		t.Fatalf("chains = %+v", chains)
	}
	chainID := chains.ActiveChainID

	for _, party := range []map[string]any{
		{"address": distributor, "role": "Distributor", "displayName": "Pakhsh Co", "region": "Tehran"},
		{"address": pharmacy, "role": "Pharmacy", "displayName": "Darou Pharmacy", "region": "Tehran"},
	} {
		expect[map[string]any](t, call(t, "POST", "/v2/roles", party), 201)
	}

	// A 100-unit lot: the manufacturer ships units [0,40) to the distributor,
	// which forwards [0,15) to the pharmacy. Both hops are partial, so the
	// lot ends up in three custody segments.
	const size = 100
	lot := fmt.Sprintf("LOT-%d", time.Now().UnixNano())
	reg := expect[struct {
		BatchID        string     `json:"batchId"`
		SegmentID      string     `json:"segmentId"`
		MerkleRoot     string     `json:"merkleRoot"`
		KeysRevealOnce bool       `json:"keysRevealOnce"`
		Units          []unitCred `json:"units"`
	}](t, call(t, "POST", "/v2/batches", map[string]any{"name": "Vitamin D3 1000IU", "lotCode": lot, "size": size}), 201)
	if len(reg.Units) != size || !reg.KeysRevealOnce {
		t.Fatalf("registration: %d units, keysRevealOnce=%v", len(reg.Units), reg.KeysRevealOnce)
	}
	seen := map[string]bool{}
	for i, u := range reg.Units {
		if int(u.Index) != i || seen[u.UnitKey] || !strings.HasSuffix(u.PublicQR, fmt.Sprintf("/%d/%s/%d", chainID, reg.BatchID, i)) {
			t.Fatalf("unit %d = %+v", i, u)
		}
		seen[u.UnitKey] = true
	}
	dup := call(t, "POST", "/v2/batches", map[string]any{"name": "Vitamin D3", "lotCode": lot, "size": 2})
	if dup.status != 409 {
		t.Fatalf("re-registering a lot: %d %s", dup.status, dup.body)
	}

	type transferResult struct {
		ToSegmentID string `json:"toSegmentId"`
		Split       bool   `json:"split"`
		Status      string `json:"status"`
		Start       uint32 `json:"start"`
		End         uint32 `json:"end"`
	}
	toDistributor := expect[transferResult](t, call(t, "POST", "/v2/segments/"+reg.SegmentID+"/transfer", map[string]any{"toAddress": distributor, "count": 40}), 201)
	if !toDistributor.Split || toDistributor.Start != 0 || toDistributor.End != 40 || toDistributor.Status != "Transferred" {
		t.Fatalf("manufacturer -> distributor = %+v", toDistributor)
	}
	skipHop := call(t, "POST", "/v2/segments/"+reg.SegmentID+"/transfer", map[string]any{"toAddress": pharmacy, "count": 5})
	if skipHop.status != 400 && skipHop.status != 409 {
		t.Fatalf("manufacturer shipping straight to a pharmacy: %d %s", skipHop.status, skipHop.body)
	}
	toPharmacy := expect[transferResult](t, call(t, "POST", "/v2/segments/"+toDistributor.ToSegmentID+"/transfer", map[string]any{"toAddress": pharmacy, "count": 15}), 201)
	if !toPharmacy.Split || toPharmacy.Start != 0 || toPharmacy.End != 15 || toPharmacy.Status != "AtPointOfSale" {
		t.Fatalf("distributor -> pharmacy = %+v", toPharmacy)
	}

	type batchDetail struct {
		Size          int              `json:"size"`
		ConsumedCount int              `json:"consumedCount"`
		Distribution  map[string]int32 `json:"distribution"`
		Segments      []struct {
			SegmentID string `json:"segmentId"`
			Owner     string `json:"owner"`
			Start     int    `json:"start"`
			End       int    `json:"end"`
			Status    string `json:"status"`
		} `json:"segments"`
	}
	detail := expect[batchDetail](t, call(t, "GET", "/v2/batches/"+reg.BatchID, nil), 200)
	if len(detail.Segments) != 3 || detail.Distribution["Created"] != 60 || detail.Distribution["Transferred"] != 25 ||
		detail.Distribution["AtPointOfSale"] != 15 || detail.Distribution["consumed"] != 0 {
		t.Fatalf("batch after two partial hops = %s", mustJSON(detail))
	}
	covered := 0
	for _, s := range detail.Segments {
		covered += s.End - s.Start
	}
	if covered != size {
		t.Fatalf("segments cover %d of %d units: %s", covered, size, mustJSON(detail.Segments))
	}
	held := expect[struct {
		Items []struct {
			SegmentID string `json:"segmentId"`
			Units     int    `json:"units"`
		} `json:"items"`
	}](t, call(t, "GET", "/v2/segments?owner="+pharmacy+"&batchId="+reg.BatchID, nil), 200)
	if len(held.Items) != 1 || held.Items[0].SegmentID != toPharmacy.ToSegmentID || held.Items[0].Units != 15 {
		t.Fatalf("pharmacy stock = %+v", held)
	}

	verifyPath := func(index int) string { return fmt.Sprintf("/v2/verify/%d/%s/%d", chainID, reg.BatchID, index) }
	buyer := []string{"X-Device-Id", "buyer-phone", "X-Scan-Region", "tehran", "Cache-Control", "no-cache"}

	before := expect[verifyResult](t, call(t, "GET", verifyPath(2), nil, buyer...), 200)
	if before.Authenticity != protocol.Authentic || before.Custodian == nil || before.Custodian.Address != pharmacy ||
		*before.Custodian.DisplayName != "Darou Pharmacy" || before.Evidence == nil || before.Evidence.MerkleRoot != reg.MerkleRoot {
		t.Fatalf("verify before sale = %s", mustJSON(before))
	}
	for _, index := range []int{20, 70} {
		inTransit := expect[verifyResult](t, call(t, "GET", verifyPath(index), nil, buyer...), 200)
		if inTransit.Authenticity != protocol.InTransit {
			t.Fatalf("unit %d not yet at a pharmacy = %+v", index, inTransit)
		}
	}

	secret, err := protocol.ParseSecretQR(reg.Units[2].SecretQR)
	if err != nil {
		t.Fatal(err)
	}
	consumer := crypto.PubkeyToAddress(mustKey(t).PublicKey)
	consume := func(index uint32, key *ecdsa.PrivateKey) response {
		deadline := time.Now().Add(10 * time.Minute).Unix()
		sig, err := chain.NewEIP712V2(chains.EIP712Domain.VerifyingContract).SignUnitConsume(key, chain.UnitConsumeAuthorization{
			BatchID: mustBig(reg.BatchID), Index: index, Consumer: consumer, Deadline: big.NewInt(deadline), ChainID: chainID,
		})
		if err != nil {
			t.Fatal(err)
		}
		return call(t, "POST", "/v2/consume", map[string]any{
			"batchId": reg.BatchID, "index": index, "consumer": consumer.Hex(), "deadline": deadline, "signature": hexutil.Encode(sig),
		})
	}

	wrongKey, _ := protocol.ParseSecretQR(reg.Units[3].SecretQR)
	if r := consume(2, wrongKey.Key); r.status != 400 {
		t.Fatalf("consume signed by another unit's key: %d %s", r.status, r.body)
	}
	consumed := expect[struct {
		Status   string `json:"status"`
		Consumer string `json:"consumer"`
		TxHash   string `json:"txHash"`
	}](t, consume(2, secret.Key), 201)
	if consumed.Status != "Consumed" || !strings.EqualFold(consumed.Consumer, consumer.Hex()) {
		t.Fatalf("consume = %+v", consumed)
	}
	if r := consume(2, secret.Key); r.status != 409 {
		t.Fatalf("refill attempt: %d %s", r.status, r.body)
	}
	notForSale, _ := protocol.ParseSecretQR(reg.Units[20].SecretQR)
	if r := consume(20, notForSale.Key); r.status != 409 {
		t.Fatalf("consume while still at the distributor: %d %s", r.status, r.body)
	}
	if d := expect[batchDetail](t, call(t, "GET", "/v2/batches/"+reg.BatchID, nil), 200); d.ConsumedCount != 1 || d.Distribution["consumed"] != 1 {
		t.Fatalf("batch after one consumption = %s", mustJSON(d))
	}

	after := expect[verifyResult](t, call(t, "GET", verifyPath(2), nil, buyer...), 200)
	if after.Authenticity != protocol.Consumed || after.Risk.Level != "low" {
		t.Fatalf("buyer re-scan after use = %s", mustJSON(after))
	}
	var clone verifyResult
	for i, region := range []string{"mashhad", "shiraz", "tabriz"} {
		clone = expect[verifyResult](t, call(t, "GET", verifyPath(2), nil, "X-Device-Id", fmt.Sprintf("clone-%d", i), "X-Scan-Region", region), 200)
	}
	if clone.Authenticity != protocol.Consumed || clone.Risk.Level != "high" {
		t.Fatalf("copied label scanned elsewhere = %s", mustJSON(clone))
	}
	suspicious := expect[struct {
		Items []struct {
			BatchID string `json:"batchId"`
			Index   int    `json:"index"`
		} `json:"items"`
	}](t, call(t, "GET", "/v2/scans/suspicious?sinceHours=1&limit=500", nil), 200)
	found := false
	for _, it := range suspicious.Items {
		found = found || (it.BatchID == reg.BatchID && it.Index == 2)
	}
	if !found {
		t.Fatalf("suspicious = %+v", suspicious)
	}

	proof := expect[struct {
		Leaf     string   `json:"leaf"`
		Proof    []string `json:"proof"`
		Consumed bool     `json:"consumed"`
	}](t, call(t, "GET", fmt.Sprintf("/v2/batches/%s/units/2/proof", reg.BatchID), nil), 200)
	if !proof.Consumed || len(proof.Proof) == 0 {
		t.Fatalf("proof = %+v", proof)
	}
	history := expect[struct {
		Custody []struct {
			To string `json:"to"`
		} `json:"custody"`
		Consumption *struct {
			TxHash string `json:"txHash"`
		} `json:"consumption"`
	}](t, call(t, "GET", fmt.Sprintf("/v2/batches/%s/units/2/history", reg.BatchID), nil), 200)
	if len(history.Custody) != 2 || history.Custody[1].To != pharmacy || history.Consumption == nil || history.Consumption.TxHash != consumed.TxHash {
		t.Fatalf("history = %+v", history)
	}

	secrets := []string{reg.Units[0].SecretQR, reg.Units[1].SecretQR, reg.Units[2].SecretQR}
	pdf := call(t, "POST", "/v2/labels/render", map[string]any{"batchId": reg.BatchID, "secretQrs": secrets})
	if pdf.status != 200 || !bytes.HasPrefix(pdf.body, []byte("%PDF")) {
		t.Fatalf("labels: %d %.200s", pdf.status, pdf.body)
	}
	forged := protocol.SecretQR(chainID, mustBig(reg.BatchID).Int64(), 0, mustKey(t))
	if r := call(t, "POST", "/v2/labels/render", map[string]any{"batchId": reg.BatchID, "secretQrs": []string{forged}}); r.status != 400 {
		t.Fatalf("forged label key: %d %s", r.status, r.body)
	}

	expect[map[string]any](t, call(t, "POST", "/v2/batches/"+reg.BatchID+"/recall", map[string]any{"reason": "e2e"}), 201)
	recalled := expect[verifyResult](t, call(t, "GET", verifyPath(0), nil, buyer...), 200)
	if recalled.Authenticity != protocol.Recalled {
		t.Fatalf("after recall = %+v", recalled)
	}
	unsold, _ := protocol.ParseSecretQR(reg.Units[0].SecretQR)
	if r := consume(0, unsold.Key); r.status != 409 {
		t.Fatalf("consume after recall: %d %s", r.status, r.body)
	}
}

func mustKey(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	key, err := crypto.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func mustBig(s string) *big.Int {
	n, ok := new(big.Int).SetString(s, 10)
	if !ok {
		panic("bad id " + s)
	}
	return n
}

func mustJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}
