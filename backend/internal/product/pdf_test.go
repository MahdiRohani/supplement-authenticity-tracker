package product

import (
	"bytes"
	"os"
	"testing"
)

// testdata/labels.pdf was rendered by the previous TypeScript implementation
// from the same lines.
func TestRenderPDFMatchesFixture(t *testing.T) {
	want, err := os.ReadFile("testdata/labels.pdf")
	if err != nil {
		t.Fatal(err)
	}
	got := RenderPDF([]string{
		"Supplement batch labels",
		`Batch: B-(1)\x`,
		"Units: 2",
		"",
		`Vitamin D3 | id=7 | status=Created | {"v":1,"productId":"7","chainId":31337}`,
		`Omega 3 é | id=pending-batch-1-0 | status=Consumed | {"v":1,"productId":"pending-batch-1-0","chainId":31337}`,
	})
	if !bytes.Equal(got, want) {
		t.Fatalf("PDF bytes differ\n got %q\nwant %q", got, want)
	}
}
