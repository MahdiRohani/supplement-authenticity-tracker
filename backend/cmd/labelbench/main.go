// Command labelbench reports the size of the two v2 label payloads and the QR
// symbols they need, for the evaluation's label-payload table.
//
// For each chain and batch size it takes the largest index of the batch (the
// longest payload) and encodes the public verify URL and the secret
// satk2:... key at error-correction level M, the level the label PDF uses.
// Module size is for the 34 mm printed symbol of the label sheet.
//
//	go run ./cmd/labelbench -out bench/results
package main

import (
	"crypto/ecdsa"
	"encoding/csv"
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strconv"

	"github.com/ethereum/go-ethereum/crypto"
	qrcode "github.com/skip2/go-qrcode"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/protocol"
)

const printedQRmm = 34.0

func main() {
	base := flag.String("base", "https://supplementtracker.aut.ir/u", "PUBLIC_VERIFY_BASE_URL")
	batchID := flag.Int64("batch", 100000, "batch id used in the payloads (a long-lived deployment's order of magnitude)")
	outDir := flag.String("out", "bench/results", "output directory")
	flag.Parse()

	key, err := crypto.GenerateKey()
	if err != nil {
		log.Fatal(err)
	}
	chains := []struct {
		name string
		id   int64
	}{{"ethereum", 1}, {"base", 8453}, {"arbitrum", 42161}, {"hardhat", 31337}}
	sizes := []int{1, 10, 100, 1000, 10000, 100000, 1 << 20}

	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		log.Fatal(err)
	}
	path := filepath.Join(*outDir, "label-payload.csv")
	f, err := os.Create(path)
	if err != nil {
		log.Fatal(err)
	}
	defer f.Close()
	w := csv.NewWriter(f)
	_ = w.Write([]string{"chain", "chain_id", "n", "label", "payload_chars", "qr_version", "modules", "module_mm"})
	for _, c := range chains {
		for _, n := range sizes {
			index := uint32(n - 1)
			public := fmt.Sprintf("%s/%d/%d/%d", *base, c.id, *batchID, index)
			secret := protocol.SecretQR(c.id, *batchID, index, key)
			for _, l := range []struct{ name, payload string }{{"public", public}, {"secret", secret}} {
				row, err := measure(c.name, c.id, n, l.name, l.payload)
				if err != nil {
					log.Fatal(err)
				}
				_ = w.Write(row)
			}
		}
	}
	w.Flush()
	if err := w.Error(); err != nil {
		log.Fatal(err)
	}
	printExample(*base, *batchID, key)
	fmt.Println("wrote", path)
}

func measure(chain string, chainID int64, n int, label, payload string) ([]string, error) {
	code, err := qrcode.New(payload, qrcode.Medium)
	if err != nil {
		return nil, err
	}
	modules := 17 + 4*code.VersionNumber
	return []string{
		chain, strconv.FormatInt(chainID, 10), strconv.Itoa(n), label, strconv.Itoa(len(payload)),
		strconv.Itoa(code.VersionNumber), strconv.Itoa(modules), strconv.FormatFloat(printedQRmm/float64(modules), 'f', 3, 64),
	}, nil
}

func printExample(base string, batchID int64, key *ecdsa.PrivateKey) {
	fmt.Printf("public: %s/%d/%d/%d\n", base, 8453, batchID, 999)
	fmt.Printf("secret: %s\n", protocol.SecretQR(8453, batchID, 999, key))
}
