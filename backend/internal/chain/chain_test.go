package chain

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/common/hexutil"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
)

const (
	hardhat0Address = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"
	hardhat0Key     = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
	hardhat1Address = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"
	hardhat1Key     = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
)

func encodeKey(b []byte) string { return hexutil.Encode(b) }

func mustDecodeHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hexutil.Decode(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func httpStatus(err error) int {
	var appErr *apperr.Error
	if errors.As(err, &appErr) {
		return appErr.Status
	}
	return 500
}

func loadRegistryABI(t *testing.T) *Artifact {
	t.Helper()
	artifact, err := LoadArtifact(filepath.Join("..", "..", "..", "packages", "abis", "SupplementRegistry.json"))
	if err != nil {
		t.Skipf("registry artifact not available: %v", err)
	}
	return artifact
}

func TestKeyStoreKeepsOrderAndRotates(t *testing.T) {
	ks := NewKeyStore()
	mixedCase := common.HexToAddress(hardhat1Address).Hex()
	active, err := ks.Reload(`{"`+mixedCase+`":"`+hardhat1Key+`","`+hardhat0Address+`":"`+hardhat0Key+`"}`, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(active) != 2 || active[0] != hardhat1Address || active[1] != hardhat0Address {
		t.Fatalf("active = %v", active)
	}

	first, ok := ks.First()
	if !ok || addressOf(first) != hardhat1Address {
		t.Fatalf("first key should follow configuration order")
	}

	if _, err := ks.Reload(`{"`+hardhat0Address+`":"`+hardhat0Key+`"}`, ""); err != nil {
		t.Fatal(err)
	}
	activeCount, previousCount := ks.Counts()
	if activeCount != 1 || previousCount != 2 {
		t.Fatalf("counts = %d/%d", activeCount, previousCount)
	}
	if _, ok := ks.Resolve(strings.ToUpper(hardhat0Address)); !ok {
		t.Fatal("resolve must be case-insensitive")
	}
}

func TestKeyStoreRejectsMalformedJSON(t *testing.T) {
	ks := NewKeyStore()
	if _, err := ks.Reload(`[1]`, ""); err == nil {
		t.Fatal("expected error for non-object")
	}
	if _, err := ks.Reload(`{}`, `{"a":`); err == nil || !strings.Contains(err.Error(), "RELAYER_KEYS_PREVIOUS_JSON") {
		t.Fatalf("expected previous-keys error, got %v", err)
	}
}

type rpcDataError struct {
	msg  string
	data any
}

func (e rpcDataError) Error() string  { return e.msg }
func (e rpcDataError) ErrorData() any { return e.data }

func TestDecodeRevertBySelector(t *testing.T) {
	artifact := loadRegistryABI(t)
	contract := NewContract(nil, artifact.ABI, common.Address{})
	id := artifact.ABI.Errors["InvalidSecret"].ID
	selector := hexutil.Encode(id[:4])

	cases := map[string]error{
		"geth string data":   rpcDataError{msg: "execution reverted", data: selector},
		"hardhat data field": rpcDataError{msg: "execution reverted", data: map[string]any{"data": selector}},
		"message fallback":   errors.New("VM Exception: reverted with custom error 'InvalidSecret()'"),
	}
	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			err := contract.decodeRevert(raw)
			var revert *RevertError
			if !errors.As(err, &revert) || revert.Name != "InvalidSecret" {
				t.Fatalf("decoded %v", err)
			}
		})
	}

	unknown := contract.decodeRevert(errors.New("connection refused"))
	var revert *RevertError
	if errors.As(unknown, &revert) {
		t.Fatal("non-revert errors must pass through")
	}
}

func TestMapChainError(t *testing.T) {
	cases := []struct {
		name    string
		status  int
		message string
	}{
		{"ProductAlreadyConsumed", 409, "Product already consumed; refill is not allowed"},
		{"InvalidSecret", 400, "Invalid scratch secret"},
		{"ProductNotConsumable", 400, "Product 5 is not consumable in its current status"},
		{"EnforcedPause", 400, "Registry is paused"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := mapChainError(&RevertError{Name: tc.name, Err: errors.New("reverted")}, "5")
			var appErr *apperr.Error
			if !errors.As(err, &appErr) || appErr.Status != tc.status || appErr.Message != tc.message {
				t.Fatalf("mapped to %v", err)
			}
		})
	}
	other := errors.New("boom")
	if mapChainError(other, "1") != other {
		t.Fatal("unknown errors must stay internal")
	}
}

func TestDeploymentsAndNetwork(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "deployments.json")
	if err := os.WriteFile(path, []byte("{\n  \"31337\": { \"address\": \" 0xabc \" },\n  \"1\": null\n}"), 0o600); err != nil {
		t.Fatal(err)
	}
	d := LoadDeployments(path)
	if string(d.All()) != `{"31337":{"address":" 0xabc "},"1":null}` {
		t.Fatalf("raw = %s", d.All())
	}
	if d.Entry(1) != nil || d.Address(31337) != "0xabc" {
		t.Fatalf("entry/address lookup failed")
	}
	if NewNetwork(31337, "", d).RegistryAddress() != "0xabc" {
		t.Fatal("expected deployment address")
	}
	if NewNetwork(31337, "0xenv", d).RegistryAddress() != "0xenv" {
		t.Fatal("REGISTRY_ADDRESS must win")
	}
	if string(LoadDeployments(filepath.Join(dir, "missing.json")).All()) != "{}" {
		t.Fatal("missing file should yield an empty map")
	}
}
