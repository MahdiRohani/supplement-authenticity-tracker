// Package merkle builds the unit Merkle trees of SupplementRegistryV2 batches.
// Trees, leaves and proofs are byte-for-byte compatible with OpenZeppelin's
// StandardMerkleTree (sorted leaves, sorted-pair hashing, double-hashed
// leaves), which is what MerkleProof.verifyCalldata checks on-chain.
package merkle

import (
	"bytes"
	"errors"
	"fmt"
	"slices"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/crypto"
)

// Hash is a 32-byte tree node.
type Hash = common.Hash

// UnitLeaf is keccak256(bytes.concat(keccak256(abi.encode(uint32 index,
// address unitKey)))), the contract's unitLeaf(index, unitKey).
func UnitLeaf(index uint32, unitKey common.Address) Hash {
	var encoded [64]byte
	encoded[28] = byte(index >> 24)
	encoded[29] = byte(index >> 16)
	encoded[30] = byte(index >> 8)
	encoded[31] = byte(index)
	copy(encoded[44:], unitKey.Bytes())
	inner := crypto.Keccak256(encoded[:])
	return crypto.Keccak256Hash(inner)
}

// Tree is a complete binary tree stored as an array (root at 0), with the
// leaves sorted by hash and placed at the end in reverse order, exactly as
// StandardMerkleTree lays it out.
type Tree struct {
	nodes []Hash
	// position maps the caller's leaf order to the node index of that leaf.
	position []int
}

// Build makes a tree over leaves given in value order (unit index order).
func Build(leaves []Hash) (*Tree, error) {
	n := len(leaves)
	if n == 0 {
		return nil, errors.New("merkle: no leaves")
	}
	order := make([]int, n)
	for i := range order {
		order[i] = i
	}
	slices.SortStableFunc(order, func(a, b int) int {
		return bytes.Compare(leaves[a][:], leaves[b][:])
	})

	nodes := make([]Hash, 2*n-1)
	position := make([]int, n)
	for sorted, value := range order {
		idx := len(nodes) - 1 - sorted
		nodes[idx] = leaves[value]
		position[value] = idx
	}
	for i := len(nodes) - 1 - n; i >= 0; i-- {
		nodes[i] = hashPair(nodes[2*i+1], nodes[2*i+2])
	}
	return &Tree{nodes: nodes, position: position}, nil
}

func (t *Tree) Root() Hash { return t.nodes[0] }

func (t *Tree) Len() int { return len(t.position) }

// Leaf returns the leaf stored for value index i.
func (t *Tree) Leaf(i int) Hash { return t.nodes[t.position[i]] }

// Proof returns the sibling path for value index i, leaf to root.
func (t *Tree) Proof(i int) ([]Hash, error) {
	if i < 0 || i >= len(t.position) {
		return nil, fmt.Errorf("merkle: index %d out of range [0,%d)", i, len(t.position))
	}
	var proof []Hash
	for idx := t.position[i]; idx > 0; idx = (idx - 1) / 2 {
		sibling := idx + 1
		if idx%2 == 0 {
			sibling = idx - 1
		}
		proof = append(proof, t.nodes[sibling])
	}
	return proof, nil
}

// Verify recomputes the root from leaf and proof (OpenZeppelin processProof).
func Verify(root, leaf Hash, proof []Hash) bool {
	computed := leaf
	for _, sibling := range proof {
		computed = hashPair(computed, sibling)
	}
	return computed == root
}

// PackProof concatenates proof nodes for compact storage.
func PackProof(proof []Hash) []byte {
	out := make([]byte, 0, len(proof)*32)
	for _, h := range proof {
		out = append(out, h[:]...)
	}
	return out
}

// UnpackProof reverses PackProof.
func UnpackProof(packed []byte) ([]Hash, error) {
	if len(packed)%32 != 0 {
		return nil, fmt.Errorf("merkle: packed proof length %d is not a multiple of 32", len(packed))
	}
	out := make([]Hash, len(packed)/32)
	for i := range out {
		copy(out[i][:], packed[i*32:(i+1)*32])
	}
	return out, nil
}

func hashPair(a, b Hash) Hash {
	if bytes.Compare(a[:], b[:]) > 0 {
		a, b = b, a
	}
	return crypto.Keccak256Hash(a[:], b[:])
}
