// Package testvectors loads the SupplementRegistryV2 reference vectors that
// contracts/scripts/export-vectors.ts writes to packages/abis/test-vectors.
// Go, Solidity and Android tests all check against the same file.
package testvectors

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
)

type Unit struct {
	Index      uint32   `json:"index"`
	PrivateKey string   `json:"privateKey"`
	Address    string   `json:"address"`
	Leaf       string   `json:"leaf"`
	Proof      []string `json:"proof"`
}

type Batch struct {
	Seed  string `json:"seed"`
	Size  int    `json:"size"`
	Root  string `json:"root"`
	Units []Unit `json:"units"`
}

type Authorization struct {
	BatchSeed  string `json:"batchSeed"`
	BatchID    string `json:"batchId"`
	Index      uint32 `json:"index"`
	Consumer   string `json:"consumer"`
	Deadline   string `json:"deadline"`
	UnitKey    string `json:"unitKey"`
	StructHash string `json:"structHash"`
	Digest     string `json:"digest"`
	Signature  string `json:"signature"`
}

type Domain struct {
	Name              string `json:"name"`
	Version           string `json:"version"`
	ChainID           string `json:"chainId"`
	VerifyingContract string `json:"verifyingContract"`
}

type V2 struct {
	ProtocolVersion string          `json:"protocolVersion"`
	Domain          Domain          `json:"domain"`
	TypeHash        string          `json:"typeHash"`
	DomainSeparator string          `json:"domainSeparator"`
	Batches         []Batch         `json:"batches"`
	Authorizations  []Authorization `json:"authorizations"`
}

// Path is the repository location of the v2 vectors file.
func Path() string {
	_, file, _, _ := runtime.Caller(0)
	return filepath.Join(filepath.Dir(file), "..", "..", "..", "packages", "abis", "test-vectors", "supplement-registry-v2.json")
}

func Load() (*V2, error) {
	data, err := os.ReadFile(Path())
	if err != nil {
		return nil, err
	}
	var v V2
	if err := json.Unmarshal(data, &v); err != nil {
		return nil, err
	}
	return &v, nil
}

// BatchBySeed returns the batch generated from seed, or nil.
func (v *V2) BatchBySeed(seed string) *Batch {
	for i := range v.Batches {
		if v.Batches[i].Seed == seed {
			return &v.Batches[i]
		}
	}
	return nil
}
