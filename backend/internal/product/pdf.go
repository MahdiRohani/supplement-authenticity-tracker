package product

import (
	"fmt"
	"strings"
)

var pdfEscaper = strings.NewReplacer(`\`, `\\`, `(`, `\(`, `)`, `\)`)

// RenderPDF writes a minimal single-page PDF (Helvetica, one text line per
// entry). Offsets in the xref table are byte offsets, so the document is
// assembled from raw bytes rather than a PDF library.
func RenderPDF(lines []string) []byte {
	escaped := make([]string, len(lines))
	for i, line := range lines {
		escaped[i] = pdfEscaper.Replace(line)
	}
	joined := strings.ReplaceAll(strings.Join(escaped, "\n"), "\n", ") Tj T* (")
	content := "BT /F1 11 Tf 50 780 Td 14 TL (" + joined + ") Tj ET"

	objects := []string{
		"1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n",
		"2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n",
		"3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources<< /Font<< /F1 5 0 R >> >> >>endobj\n",
		fmt.Sprintf("4 0 obj<< /Length %d >>stream\n%s\nendstream\nendobj\n", len(content), content),
		"5 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\n",
	}

	var pdf strings.Builder
	pdf.WriteString("%PDF-1.4\n")
	offsets := make([]int, 0, len(objects))
	for _, obj := range objects {
		offsets = append(offsets, pdf.Len())
		pdf.WriteString(obj)
	}
	xrefStart := pdf.Len()
	fmt.Fprintf(&pdf, "xref\n0 %d\n", len(objects)+1)
	pdf.WriteString("0000000000 65535 f \n")
	for _, off := range offsets {
		fmt.Fprintf(&pdf, "%010d 00000 n \n", off)
	}
	fmt.Fprintf(&pdf, "trailer<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF", len(objects)+1, xrefStart)
	return []byte(pdf.String())
}
