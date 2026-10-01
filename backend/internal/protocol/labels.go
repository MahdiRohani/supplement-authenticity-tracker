package protocol

import (
	"bytes"
	"context"
	"fmt"
	"strings"

	"github.com/ethereum/go-ethereum/crypto"
	"github.com/go-pdf/fpdf"
	qrcode "github.com/skip2/go-qrcode"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// MaxLabelsPerRender bounds one PDF request (and its body size).
const MaxLabelsPerRender = 400

type RenderLabelsInput struct {
	BatchID string
	// SecretQRs are hidden-label payloads from the registration response.
	// Keys are never stored, so the caller supplies them; each one is checked
	// against the unit key committed on-chain before it is printed.
	SecretQRs []string
}

type labelData struct {
	index    uint32
	publicQR string
	secretQR string
}

func (s *Service) RenderLabels(ctx context.Context, in RenderLabelsInput) ([]byte, error) {
	batchID, err := ParseID("batchId", in.BatchID)
	if err != nil {
		return nil, err
	}
	if len(in.SecretQRs) == 0 || len(in.SecretQRs) > MaxLabelsPerRender {
		return nil, apperr.BadRequest(fmt.Sprintf("secretQrs must contain 1-%d labels", MaxLabelsPerRender))
	}
	batch, err := s.batch(ctx, batchID)
	if err != nil {
		return nil, err
	}
	labels := make([]labelData, 0, len(in.SecretQRs))
	for i, raw := range in.SecretQRs {
		reject := func(why string) error {
			return apperr.BadRequest(fmt.Sprintf("secretQrs[%d] %s", i, why))
		}
		p, err := ParseSecretQR(raw)
		if err != nil {
			return nil, reject("is not a hidden unit label")
		}
		if p.ChainID != s.opts.ChainID || p.BatchID != batchID {
			return nil, reject("belongs to another batch")
		}
		if int64(p.Index) >= int64(batch.Size) {
			return nil, reject("is outside the batch")
		}
		u, err := s.unit(ctx, batch, p.Index)
		if err != nil {
			return nil, err
		}
		if !strings.EqualFold(crypto.PubkeyToAddress(p.Key.PublicKey).Hex(), u.UnitKey) {
			return nil, reject("does not match the unit key registered on-chain")
		}
		labels = append(labels, labelData{
			index:    p.Index,
			publicQR: s.PublicQR(batchID, p.Index),
			secretQR: strings.TrimSpace(raw),
		})
	}
	return renderLabelSheet(batch, labels)
}

// Label sheet geometry (mm): A4 portrait, 2 x 4 labels per page.
const (
	sheetMargin = 10.0
	labelCols   = 2
	labelRows   = 4
	labelW      = (210 - 2*sheetMargin) / labelCols
	labelH      = (297 - 2*sheetMargin) / labelRows
	qrSize      = 34.0
)

func renderLabelSheet(batch db.Batch, labels []labelData) ([]byte, error) {
	pdf := fpdf.New("P", "mm", "A4", "")
	pdf.SetMargins(sheetMargin, sheetMargin, sheetMargin)
	pdf.SetAutoPageBreak(false, 0)
	pdf.SetTitle(fmt.Sprintf("Batch %d labels", batch.BatchID), false)
	pdf.SetCreator("supplement-authenticity-tracker", false)

	name := latin1(deref(batch.Name, "Supplement"))
	lot := latin1(deref(batch.LotCode, "-"))
	for i, l := range labels {
		slot := i % (labelCols * labelRows)
		if slot == 0 {
			pdf.AddPage()
		}
		x := sheetMargin + float64(slot%labelCols)*labelW
		y := sheetMargin + float64(slot/labelCols)*labelH

		pdf.SetDrawColor(180, 180, 180)
		pdf.SetLineWidth(0.1)
		pdf.SetDashPattern(nil, 0)
		pdf.Rect(x, y, labelW, labelH, "D")

		pdf.SetTextColor(0, 0, 0)
		pdf.SetFont("Helvetica", "B", 10)
		pdf.Text(x+4, y+7, fit(pdf, name, labelW-8))
		pdf.SetFont("Helvetica", "", 7)
		pdf.Text(x+4, y+11, fit(pdf, fmt.Sprintf("Lot %s  |  Unit #%d  |  Batch %d", lot, l.index, batch.BatchID), labelW-8))

		if err := drawQR(pdf, l.publicQR, x+4, y+14, qrSize); err != nil {
			return nil, err
		}
		pdf.SetFont("Helvetica", "B", 7)
		pdf.Text(x+4, y+52, "SCAN TO VERIFY")
		pdf.SetFont("Helvetica", "", 6)
		pdf.Text(x+4, y+55.5, "Checks origin, custody and recalls")

		boxX, boxY := x+labelW/2+2, y+12.0
		pdf.SetDrawColor(0, 0, 0)
		pdf.SetLineWidth(0.3)
		pdf.SetDashPattern([]float64{1.5, 1}, 0)
		pdf.Rect(boxX, boxY, qrSize+5, qrSize+17, "D")
		pdf.SetDashPattern(nil, 0)
		if err := drawQR(pdf, l.secretQR, boxX+2.5, boxY+2.5, qrSize); err != nil {
			return nil, err
		}
		pdf.SetFont("Helvetica", "B", 6.5)
		pdf.Text(boxX+2.5, boxY+qrSize+7, "SCRATCH AFTER PURCHASE")
		pdf.SetFont("Helvetica", "", 5.5)
		pdf.Text(boxX+2.5, boxY+qrSize+10.5, "One-time key: scan only to mark")
		pdf.Text(boxX+2.5, boxY+qrSize+13.5, "this unit as used")
	}

	var buf bytes.Buffer
	if err := pdf.Output(&buf); err != nil {
		return nil, fmt.Errorf("render labels: %w", err)
	}
	return buf.Bytes(), nil
}

// drawQR draws content as vector modules, merging horizontal runs, so labels
// stay sharp at any print resolution.
func drawQR(pdf *fpdf.Fpdf, content string, x, y, size float64) error {
	code, err := qrcode.New(content, qrcode.Medium)
	if err != nil {
		return fmt.Errorf("encode QR: %w", err)
	}
	code.DisableBorder = true
	bitmap := code.Bitmap()
	module := size / float64(len(bitmap))
	pdf.SetFillColor(0, 0, 0)
	for row, cells := range bitmap {
		for col := 0; col < len(cells); {
			if !cells[col] {
				col++
				continue
			}
			start := col
			for col < len(cells) && cells[col] {
				col++
			}
			pdf.Rect(x+float64(start)*module, y+float64(row)*module, float64(col-start)*module, module, "F")
		}
	}
	return nil
}

// latin1 keeps text printable with the PDF core fonts; other scripts are
// replaced since the label's QR codes, not its text, carry the identity.
func latin1(s string) string {
	return strings.Map(func(r rune) rune {
		if r >= 0x20 && r < 0x7f {
			return r
		}
		return '?'
	}, s)
}

func fit(pdf *fpdf.Fpdf, s string, width float64) string {
	if pdf.GetStringWidth(s) <= width {
		return s
	}
	r := []rune(s)
	for len(r) > 0 && pdf.GetStringWidth(string(r)+"...") > width {
		r = r[:len(r)-1]
	}
	return string(r) + "..."
}

func deref(s *string, fallback string) string {
	if s == nil || *s == "" {
		return fallback
	}
	return *s
}
