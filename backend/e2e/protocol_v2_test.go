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

	lot := fmt.Sprintf("LOT-%d", time.Now().UnixNano())
	reg := expect[struct {
		BatchID        string     `json:"batchId"`
		SegmentID      string     `json:"segmentId"`
		MerkleRoot     string     `json:"merkleRoot"`
		KeysRevealOnce bool       `json:"keysRevealOnce"`
		Units          []unitCred `json:"units"`
	}](t, call(t, "POST", "/v2/batches", map[string]any{"name": "Vitamin D3 1000IU", "lotCode": lot, "size": 10}), 201)
	if len(reg.Units) != 10 || !reg.KeysRevealOnce {
		t.Fatalf("registration = %+v", reg)
	}
	dup := call(t, "POST", "/v2/batches", map[string]any{"name": "Vitamin D3", "lotCode": lot, "size": 2})
	if dup.status != 409 {
		t.Fatalf("re-registering a lot: %d %s", dup.status, dup.body)
	}

	split := expect[struct {
		ToSegmentID string `json:"toSegmentId"`
		Split       bool   `json:"split"`
		Start, End  uint32
	}](t, call(t, "POST", "/v2/segments/"+reg.SegmentID+"/transfer", map[string]any{"toAddress": distributor, "count": 4}), 201)
	if !split.Split || split.Start != 0 || split.End != 4 {
		t.Fatalf("split = %+v", split)
	}
	toPharmacy := expect[struct {
		Status string `json:"status"`
		Split  bool   `json:"split"`
	}](t, call(t, "POST", "/v2/segments/"+split.ToSegmentID+"/transfer", map[string]any{"toAddress": pharmacy}), 201)
	if toPharmacy.Status != "AtPointOfSale" || toPharmacy.Split {
		t.Fatalf("to pharmacy = %+v", toPharmacy)
	}

	verifyPath := func(index int) string { return fmt.Sprintf("/v2/verify/%d/%s/%d", chainID, reg.BatchID, index) }
	buyer := []string{"X-Device-Id", "buyer-phone", "X-Scan-Region", "tehran", "Cache-Control", "no-cache"}

	before := expect[verifyResult](t, call(t, "GET", verifyPath(2), nil, buyer...), 200)
	if before.Authenticity != protocol.Authentic || before.Custodian == nil || before.Custodian.Address != pharmacy ||
		*before.Custodian.DisplayName != "Darou Pharmacy" || before.Evidence == nil || before.Evidence.MerkleRoot != reg.MerkleRoot {
		t.Fatalf("verify before sale = %s", mustJSON(before))
	}
	inTransit := expect[verifyResult](t, call(t, "GET", verifyPath(5), nil, buyer...), 200)
	if inTransit.Authenticity != protocol.InTransit {
		t.Fatalf("unit still at the manufacturer = %+v", inTransit)
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
	notForSale, _ := protocol.ParseSecretQR(reg.Units[6].SecretQR)
	if r := consume(6, notForSale.Key); r.status != 409 {
		t.Fatalf("consume before reaching a pharmacy: %d %s", r.status, r.body)
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
