package merkle

import (
	"fmt"
	"math/big"
	"testing"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/crypto"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/testvectors"
)

func TestMatchesOpenZeppelinVectors(t *testing.T) {
	v, err := testvectors.Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(v.Batches) == 0 {
		t.Fatal("no vector batches")
	}
	for _, batch := range v.Batches {
		t.Run(batch.Seed, func(t *testing.T) {
			leaves := make([]Hash, len(batch.Units))
			for i, u := range batch.Units {
				leaf := UnitLeaf(u.Index, common.HexToAddress(u.Address))
				if leaf.Hex() != u.Leaf {
					t.Fatalf("unit %d leaf = %s, want %s", u.Index, leaf.Hex(), u.Leaf)
				}
				leaves[i] = leaf
			}
			tree, err := Build(leaves)
			if err != nil {
				t.Fatal(err)
			}
			if tree.Root().Hex() != batch.Root {
				t.Fatalf("root = %s, want %s", tree.Root().Hex(), batch.Root)
			}
			for i, u := range batch.Units {
				proof, err := tree.Proof(i)
				if err != nil {
					t.Fatal(err)
				}
				if len(proof) != len(u.Proof) {
					t.Fatalf("unit %d proof length = %d, want %d", u.Index, len(proof), len(u.Proof))
				}
				for j := range proof {
					if proof[j].Hex() != u.Proof[j] {
						t.Fatalf("unit %d proof[%d] = %s, want %s", u.Index, j, proof[j].Hex(), u.Proof[j])
					}
				}
				if !Verify(tree.Root(), leaves[i], proof) {
					t.Fatalf("unit %d proof does not verify", u.Index)
				}
			}
		})
	}
}

func TestVectorKeysDeriveAddresses(t *testing.T) {
	v, err := testvectors.Load()
	if err != nil {
		t.Fatal(err)
	}
	for _, batch := range v.Batches {
		for _, u := range batch.Units {
			want := crypto.Keccak256Hash([]byte(fmt.Sprintf("%s:%d", batch.Seed, u.Index)))
			if want.Hex() != u.PrivateKey {
				t.Fatalf("%s/%d private key derivation drifted", batch.Seed, u.Index)
			}
			key, err := crypto.ToECDSA(want[:])
			if err != nil {
				t.Fatal(err)
			}
			if crypto.PubkeyToAddress(key.PublicKey) != common.HexToAddress(u.Address) {
				t.Fatalf("%s/%d address mismatch", batch.Seed, u.Index)
			}
		}
	}
}

func TestProofsVerifyForManySizes(t *testing.T) {
	for _, n := range []int{1, 2, 3, 4, 7, 16, 33, 100, 257} {
		leaves := make([]Hash, n)
		for i := range leaves {
			leaves[i] = UnitLeaf(uint32(i), common.BigToAddress(new(big.Int).Lsh(big.NewInt(1), uint(i%150))))
		}
		tree, err := Build(leaves)
		if err != nil {
			t.Fatal(err)
		}
		maxDepth := 0
		for i := range leaves {
			proof, err := tree.Proof(i)
			if err != nil {
				t.Fatal(err)
			}
			maxDepth = max(maxDepth, len(proof))
			if !Verify(tree.Root(), tree.Leaf(i), proof) {
				t.Fatalf("n=%d: proof %d fails", n, i)
			}
			packed, err := UnpackProof(PackProof(proof))
			if err != nil || len(packed) != len(proof) {
				t.Fatalf("pack round trip: %v", err)
			}
			wrong := UnitLeaf(uint32(i)+1, common.Address{0xde, 0xad})
			if Verify(tree.Root(), wrong, proof) {
				t.Fatalf("n=%d: forged leaf %d verified", n, i)
			}
		}
		if ceil := ceilLog2(n); maxDepth > ceil {
			t.Fatalf("n=%d: depth %d exceeds ceil(log2 n)=%d", n, maxDepth, ceil)
		}
	}
	if _, err := Build(nil); err == nil {
		t.Fatal("empty tree must fail")
	}
	tree, _ := Build([]Hash{UnitLeaf(0, common.Address{1})})
	if _, err := tree.Proof(1); err == nil {
		t.Fatal("out-of-range proof must fail")
	}
	if _, err := UnpackProof(make([]byte, 33)); err == nil {
		t.Fatal("ragged packed proof must fail")
	}
}

func ceilLog2(n int) int {
	d := 0
	for (1 << d) < n {
		d++
	}
	return d
}
