// Package chain talks to the SupplementRegistry contract: reads, relayed
// writes, event indexing and EIP-712 signatures.
package chain

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/ethereum/go-ethereum/accounts/abi"
)

// Artifact is the exported contract description in packages/abis.
type Artifact struct {
	Address    string
	ABIVersion string
	ABI        abi.ABI
	// DeployBlock is 0 when the artifact predates the field.
	DeployBlock uint64
}

func LoadArtifact(path string) (*Artifact, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read registry artifact: %w", err)
	}
	var raw struct {
		Address     string          `json:"address"`
		ABIVersion  string          `json:"abiVersion"`
		ABI         json.RawMessage `json:"abi"`
		DeployBlock uint64          `json:"deployBlock"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("decode registry artifact: %w", err)
	}
	parsed, err := abi.JSON(bytes.NewReader(raw.ABI))
	if err != nil {
		return nil, fmt.Errorf("parse registry ABI: %w", err)
	}
	return &Artifact{Address: raw.Address, ABIVersion: raw.ABIVersion, ABI: parsed, DeployBlock: raw.DeployBlock}, nil
}

// ContractDeployment is one entry of a chain's `contracts` map.
type ContractDeployment struct {
	ABIVersion  string `json:"abiVersion"`
	Address     string `json:"address"`
	DeployBlock uint64 `json:"deployBlock"`
}

// Deployments is the multi-chain address map from deployments.json. The raw
// document is kept so it can be served back verbatim.
type Deployments struct {
	raw     json.RawMessage
	entries map[string]json.RawMessage
}

// LoadDeployments never fails: a missing or malformed file means no known
// deployments.
func LoadDeployments(path string) *Deployments {
	d := &Deployments{raw: json.RawMessage("{}"), entries: map[string]json.RawMessage{}}
	data, err := os.ReadFile(path)
	if err != nil {
		return d
	}
	var entries map[string]json.RawMessage
	if err := json.Unmarshal(data, &entries); err != nil {
		return d
	}
	var compact bytes.Buffer
	if err := json.Compact(&compact, data); err != nil {
		return d
	}
	d.raw = compact.Bytes()
	d.entries = entries
	return d
}

func (d *Deployments) All() json.RawMessage { return d.raw }

// Entry returns the deployment document for chainID, or nil.
func (d *Deployments) Entry(chainID int64) json.RawMessage {
	entry, ok := d.entries[strconv.FormatInt(chainID, 10)]
	if !ok || string(entry) == "null" {
		return nil
	}
	return entry
}

func (d *Deployments) Address(chainID int64) string {
	var entry struct {
		Address string `json:"address"`
	}
	if raw := d.Entry(chainID); raw != nil {
		_ = json.Unmarshal(raw, &entry)
	}
	return strings.TrimSpace(entry.Address)
}

// Contract returns the named contract from the chain's `contracts` map.
func (d *Deployments) Contract(chainID int64, name string) (ContractDeployment, bool) {
	var entry struct {
		Contracts map[string]ContractDeployment `json:"contracts"`
	}
	raw := d.Entry(chainID)
	if raw == nil || json.Unmarshal(raw, &entry) != nil {
		return ContractDeployment{}, false
	}
	c, ok := entry.Contracts[name]
	c.Address = strings.TrimSpace(c.Address)
	return c, ok && c.Address != ""
}

// RegistryV2Name is the contract name used in deployments.json.
const RegistryV2Name = "SupplementRegistryV2"

// Network answers "which registry on which chain" questions.
type Network struct {
	ChainID       int64
	envRegistry   string
	envRegistryV2 string
	Deployments   *Deployments
}

func NewNetwork(chainID int64, envRegistry string, deployments *Deployments) *Network {
	return &Network{ChainID: chainID, envRegistry: envRegistry, Deployments: deployments}
}

// WithRegistryV2 sets the REGISTRY_V2_ADDRESS override.
func (n *Network) WithRegistryV2(envAddress string) *Network {
	n.envRegistryV2 = envAddress
	return n
}

// RegistryV2 resolves the v2 registry: REGISTRY_V2_ADDRESS first, then the
// active chain's deployments.json entry (which also supplies deployBlock).
func (n *Network) RegistryV2() ContractDeployment {
	deployed, _ := n.Deployments.Contract(n.ChainID, RegistryV2Name)
	if env := strings.TrimSpace(n.envRegistryV2); env != "" {
		if !strings.EqualFold(env, deployed.Address) {
			deployed = ContractDeployment{ABIVersion: deployed.ABIVersion}
		}
		deployed.Address = env
	}
	return deployed
}

// RegistryAddress prefers REGISTRY_ADDRESS, then the deployment for the active
// chain.
func (n *Network) RegistryAddress() string {
	if env := strings.TrimSpace(n.envRegistry); env != "" {
		return env
	}
	return n.Deployments.Address(n.ChainID)
}
