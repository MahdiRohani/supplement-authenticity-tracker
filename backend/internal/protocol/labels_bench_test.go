package protocol

import (
	"fmt"
	"testing"

	"github.com/ethereum/go-ethereum/crypto"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// BenchmarkRenderLabelSheet reports render time and PDF bytes per label for
// one full page, ten pages and the per-request maximum:
//
//	go test -run '^$' -bench RenderLabelSheet -benchtime 20x ./internal/protocol/
func BenchmarkRenderLabelSheet(b *testing.B) {
	name, lot := "Vitamin D3 1000 IU", "LOT-2026-10-A"
	batch := db.Batch{BatchID: 100000, Size: MaxLabelsPerRender, Name: &name, LotCode: &lot}
	labels := make([]labelData, MaxLabelsPerRender)
	for i := range labels {
		key, err := crypto.GenerateKey()
		if err != nil {
			b.Fatal(err)
		}
		labels[i] = labelData{
			index:    uint32(i),
			publicQR: fmt.Sprintf("https://supplementtracker.aut.ir/u/8453/%d/%d", batch.BatchID, i),
			secretQR: SecretQR(8453, batch.BatchID, uint32(i), key),
		}
	}
	for _, count := range []int{8, 80, MaxLabelsPerRender} {
		b.Run(fmt.Sprintf("labels=%d", count), func(b *testing.B) {
			var size int
			for b.Loop() {
				pdf, err := renderLabelSheet(batch, labels[:count])
				if err != nil {
					b.Fatal(err)
				}
				size = len(pdf)
			}
			b.ReportMetric(float64(size)/float64(count), "pdf_bytes/label")
			b.ReportMetric(float64(b.Elapsed().Microseconds())/float64(b.N)/float64(count)/1000, "ms/label")
		})
	}
}
