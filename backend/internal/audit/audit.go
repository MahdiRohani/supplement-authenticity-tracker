// Package audit writes AuditLog rows for sensitive actions.
package audit

import (
	"context"
	"strings"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// Entry is one audit record. Detail may be a string (stored verbatim) or any
// JSON-encodable value.
type Entry struct {
	Action   string
	EntityID string
	Actor    string
	Detail   any
}

type Store interface {
	CreateAuditLog(ctx context.Context, arg db.CreateAuditLogParams) error
}

type Recorder struct {
	store Store
}

func NewRecorder(store Store) *Recorder {
	return &Recorder{store: store}
}

func (r *Recorder) Record(ctx context.Context, e Entry) error {
	var detail *string
	switch d := e.Detail.(type) {
	case nil:
	case string:
		detail = &d
	default:
		encoded, err := jsonx.Marshal(d)
		if err != nil {
			return err
		}
		s := string(encoded)
		detail = &s
	}
	return r.store.CreateAuditLog(ctx, db.CreateAuditLogParams{
		ID:       cuid.New(),
		Action:   e.Action,
		EntityID: nonEmpty(e.EntityID),
		Actor:    nonEmpty(strings.ToLower(e.Actor)),
		Detail:   detail,
		Now:      jsonx.Now(),
	})
}

func nonEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
