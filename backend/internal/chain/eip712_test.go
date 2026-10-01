package chain

import (
	"strings"
	"testing"

	"github.com/ethereum/go-ethereum/crypto"
)

// Vectors produced by the previous ethers v6 implementation.
const (
	fixtureRegistry     = "0x5FbDB2315678afecb367f032d93F642f64180aa3"
	fixturePrivateKey   = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
	fixtureManufacturer = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
	fixtureMetadataHash = "0x8c1502efdfb3eb924223e28a805f627a0373abad66ef963463dfb1d86bc859cb"
	fixtureMetadataSig  = "0xa4f2f505b78216b264c2bdd8650320ea0ae1ff27ca2a7601e763de0c353dd5927977f73968367f220a2d9ccce0123980078c94468e78ba6eca9aad3503d218a91b"
	fixtureZeroDomSig   = "0x4d385c9cf625a9d862c589713f36d44e5765bbff06ab9c4ea9515816167b710d2b799a931e7c11863f32c022dfdfb6f6258b02a57e24631af22e3f1ca4ddd0731c"
	fixtureConsumer     = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
	fixtureSecret       = "0x1111111111111111111111111111111111111111111111111111111111111111"
	fixtureConsumeSig   = "0xfa7c89645034c292b5d2d910a72d3ff09927b2aeb2e6c96dc6633e5e4252755f3fad55d1c4f39f627c91b162c8350abe0aeb2b3ba7bb4de5659316bd48c6d19d1b"
)

func TestSignManufacturerMetadataMatchesEthers(t *testing.T) {
	sig, err := NewEIP712(fixtureRegistry).SignManufacturerMetadata(fixturePrivateKey, ManufacturerMetadata{
		MetadataHash: fixtureMetadataHash,
		Manufacturer: fixtureManufacturer,
		ChainID:      31337,
		Nonce:        1,
	})
	if err != nil {
		t.Fatal(err)
	}
	if sig != fixtureMetadataSig {
		t.Fatalf("signature mismatch\n got %s\nwant %s", sig, fixtureMetadataSig)
	}
}

func TestSignWithoutRegistryUsesZeroAddressDomain(t *testing.T) {
	sig, err := NewEIP712("").SignManufacturerMetadata(fixturePrivateKey, ManufacturerMetadata{
		MetadataHash: fixtureMetadataHash,
		Manufacturer: fixtureManufacturer,
		ChainID:      11155111,
		Nonce:        0,
	})
	if err != nil {
		t.Fatal(err)
	}
	if sig != fixtureZeroDomSig {
		t.Fatalf("signature mismatch\n got %s\nwant %s", sig, fixtureZeroDomSig)
	}
}

func TestMetadataRoundTripWithRandomKey(t *testing.T) {
	key, err := crypto.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	address := crypto.PubkeyToAddress(key.PublicKey).Hex()
	signer := NewEIP712(fixtureRegistry)
	m := ManufacturerMetadata{
		MetadataHash: crypto.Keccak256Hash([]byte("demo-meta")).Hex(),
		Manufacturer: address,
		ChainID:      31337,
		Nonce:        1,
	}
	sig, err := signer.SignManufacturerMetadata(strings.TrimPrefix(encodeKey(crypto.FromECDSA(key)), "0x"), m)
	if err != nil {
		t.Fatal(err)
	}
	recovered, err := signer.RecoverManufacturerMetadata(sig, m)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.EqualFold(recovered.Hex(), address) {
		t.Fatalf("recovered %s, want %s", recovered.Hex(), address)
	}

	m.Nonce = 2
	other, err := signer.RecoverManufacturerMetadata(sig, m)
	if err != nil {
		t.Fatal(err)
	}
	if strings.EqualFold(other.Hex(), address) {
		t.Fatal("changing the nonce must change the recovered signer")
	}
}

func TestRecoverConsumeAuthorizationMatchesEthers(t *testing.T) {
	recovered, err := NewEIP712(fixtureRegistry).RecoverConsumeAuthorization(fixtureConsumeSig, ConsumeAuthorization{
		ProductID: "7",
		Secret:    fixtureSecret,
		Consumer:  fixtureConsumer,
		Deadline:  4102444800,
		ChainID:   31337,
	})
	if err != nil {
		t.Fatal(err)
	}
	if recovered.Hex() != fixtureConsumer {
		t.Fatalf("recovered %s, want %s", recovered.Hex(), fixtureConsumer)
	}
}

func TestRecoverAcceptsCompactSignature(t *testing.T) {
	signer := NewEIP712(fixtureRegistry)
	m := ManufacturerMetadata{MetadataHash: fixtureMetadataHash, Manufacturer: fixtureManufacturer, ChainID: 31337, Nonce: 1}
	full := mustDecodeHex(t, fixtureMetadataSig)
	compact := make([]byte, 64)
	copy(compact, full[:64])
	if full[64] == 28 {
		compact[32] |= 0x80
	}
	recovered, err := signer.RecoverManufacturerMetadata(encodeKey(compact), m)
	if err != nil {
		t.Fatal(err)
	}
	if recovered.Hex() != fixtureManufacturer {
		t.Fatalf("recovered %s", recovered.Hex())
	}
}

func TestInvalidInputsAreBadRequests(t *testing.T) {
	signer := NewEIP712(fixtureRegistry)
	valid := ManufacturerMetadata{MetadataHash: fixtureMetadataHash, Manufacturer: fixtureManufacturer, ChainID: 31337, Nonce: 1}
	cases := map[string]func() error{
		"garbage signature": func() error {
			_, err := signer.RecoverManufacturerMetadata("not-a-signature", valid)
			return err
		},
		"short signature": func() error {
			_, err := signer.RecoverManufacturerMetadata("0x1234", valid)
			return err
		},
		"bad private key": func() error {
			_, err := signer.SignManufacturerMetadata("0xzz", valid)
			return err
		},
		"bad metadata hash": func() error {
			m := valid
			m.MetadataHash = "0x1234"
			_, err := signer.SignManufacturerMetadata(fixturePrivateKey, m)
			return err
		},
		"bad product id": func() error {
			_, err := signer.RecoverConsumeAuthorization(fixtureConsumeSig, ConsumeAuthorization{
				ProductID: "abc", Secret: fixtureSecret, Consumer: fixtureConsumer, Deadline: 1, ChainID: 31337,
			})
			return err
		},
	}
	for name, run := range cases {
		t.Run(name, func(t *testing.T) {
			err := run()
			if err == nil {
				t.Fatal("expected error")
			}
			if status := httpStatus(err); status != 400 {
				t.Fatalf("status = %d (%v)", status, err)
			}
		})
	}
}
