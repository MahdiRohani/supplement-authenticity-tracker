package chain

import (
	"math/big"
	"strconv"
	"testing"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/crypto"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/testvectors"
)

func vectorAuthorization(t *testing.T, v *testvectors.V2, a testvectors.Authorization) UnitConsumeAuthorization {
	t.Helper()
	batchID, ok := new(big.Int).SetString(a.BatchID, 10)
	if !ok {
		t.Fatalf("batchId %q", a.BatchID)
	}
	deadline, ok := new(big.Int).SetString(a.Deadline, 10)
	if !ok {
		t.Fatalf("deadline %q", a.Deadline)
	}
	chainID, err := strconv.ParseInt(v.Domain.ChainID, 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	return UnitConsumeAuthorization{
		BatchID:  batchID,
		Index:    a.Index,
		Consumer: common.HexToAddress(a.Consumer),
		Deadline: deadline,
		ChainID:  chainID,
	}
}

func TestUnitConsumeMatchesVectors(t *testing.T) {
	v, err := testvectors.Load()
	if err != nil {
		t.Fatal(err)
	}
	if v.Domain.Name != domainName || v.Domain.Version != domainVersionV2 {
		t.Fatalf("vector domain = %+v", v.Domain)
	}
	typeHash := crypto.Keccak256Hash([]byte("ConsumeAuthorization(uint256 batchId,uint32 index,address consumer,uint256 deadline)"))
	if typeHash.Hex() != v.TypeHash {
		t.Fatalf("type hash = %s, want %s", typeHash.Hex(), v.TypeHash)
	}
	domain := NewEIP712V2(v.Domain.VerifyingContract)
	if len(v.Authorizations) == 0 {
		t.Fatal("no vector authorizations")
	}
	for _, a := range v.Authorizations {
		auth := vectorAuthorization(t, v, a)
		digest, err := domain.UnitConsumeDigest(auth)
		if err != nil {
			t.Fatal(err)
		}
		if digest.Hex() != a.Digest {
			t.Fatalf("index %d digest = %s, want %s", a.Index, digest.Hex(), a.Digest)
		}

		sig := hexutil.MustDecode(a.Signature)
		signer, normalized, err := domain.RecoverUnitConsume(sig, auth)
		if err != nil {
			t.Fatal(err)
		}
		if signer != common.HexToAddress(a.UnitKey) {
			t.Fatalf("index %d recovered %s, want %s", a.Index, signer.Hex(), a.UnitKey)
		}
		if hexutil.Encode(normalized) != a.Signature {
			t.Fatal("a canonical signature must be passed through unchanged")
		}

		unit := v.BatchBySeed(a.BatchSeed).Units[a.Index]
		key, err := crypto.HexToECDSA(unit.PrivateKey[2:])
		if err != nil {
			t.Fatal(err)
		}
		ours, err := domain.SignUnitConsume(key, auth)
		if err != nil {
			t.Fatal(err)
		}
		if hexutil.Encode(ours) != a.Signature {
			t.Fatalf("index %d signature differs from ethers", a.Index)
		}
	}
}

func TestUnitConsumeRejectsWrongDomainAndMalleableSignatures(t *testing.T) {
	v, err := testvectors.Load()
	if err != nil {
		t.Fatal(err)
	}
	a := v.Authorizations[0]
	auth := vectorAuthorization(t, v, a)
	sig := hexutil.MustDecode(a.Signature)

	otherContract := NewEIP712V2("0x5FbDB2315678afecb367f032d93F642f64180aa3")
	if signer, _, err := otherContract.RecoverUnitConsume(sig, auth); err != nil || signer == common.HexToAddress(a.UnitKey) {
		t.Fatalf("signature must not transfer to another deployment: %s %v", signer.Hex(), err)
	}
	v1 := NewEIP712(v.Domain.VerifyingContract)
	if signer, _, _ := v1.RecoverUnitConsume(sig, auth); signer == common.HexToAddress(a.UnitKey) {
		t.Fatal("signature must not verify under the v1 domain")
	}
	otherConsumer := auth
	otherConsumer.Consumer = common.HexToAddress("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
	domain := NewEIP712V2(v.Domain.VerifyingContract)
	if signer, _, _ := domain.RecoverUnitConsume(sig, otherConsumer); signer == common.HexToAddress(a.UnitKey) {
		t.Fatal("a front-runner must not be able to swap the consumer")
	}

	lowV := append([]byte(nil), sig...)
	lowV[64] -= 27
	signer, normalized, err := domain.RecoverUnitConsume(lowV, auth)
	if err != nil || signer != common.HexToAddress(a.UnitKey) || normalized[64] < 27 {
		t.Fatalf("v=0/1 should be normalized: %v", err)
	}

	// s' = n - s with flipped v recovers the same key but is rejected
	// on-chain, so it must be rejected here too.
	n := crypto.S256().Params().N
	s := new(big.Int).SetBytes(sig[32:64])
	high := append([]byte(nil), sig...)
	copy(high[32:64], common.LeftPadBytes(new(big.Int).Sub(n, s).Bytes(), 32))
	high[64] = 27 + (1 - (sig[64] - 27))
	if _, _, err := domain.RecoverUnitConsume(high, auth); err == nil {
		t.Fatal("high-s signature must be rejected")
	}
	if _, _, err := domain.RecoverUnitConsume(sig[:64], auth); err == nil {
		t.Fatal("64-byte signatures are not accepted by the contract")
	}
	bad := auth
	bad.BatchID = big.NewInt(0)
	if _, err := domain.UnitConsumeDigest(bad); err == nil {
		t.Fatal("batchId 0 must be rejected")
	}
}
