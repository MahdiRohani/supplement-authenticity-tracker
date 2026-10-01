// Package roles manages off-chain supply-chain role bindings for wallets.
package roles

import (
	"context"
	"strings"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

type Store interface {
	ListRoleBindings(ctx context.Context) ([]db.RoleBinding, error)
	ListRoleBindingsForAddress(ctx context.Context, address string) ([]db.RoleBinding, error)
	UpsertRoleBinding(ctx context.Context, arg db.UpsertRoleBindingParams) (db.RoleBinding, error)
	DeleteRoleBinding(ctx context.Context, arg db.DeleteRoleBindingParams) (int64, error)
}

type Binding struct {
	ID        string `json:"id"`
	Address   string `json:"address"`
	Role      string `json:"role"`
	CreatedAt string `json:"createdAt"`
	UpdatedAt string `json:"updatedAt"`
}

type Deleted struct {
	Deleted bool `json:"deleted"`
}

type Service struct {
	store Store
}

func NewService(st Store) *Service {
	return &Service{store: st}
}

func (s *Service) List(ctx context.Context) ([]Binding, error) {
	rows, err := s.store.ListRoleBindings(ctx)
	return toBindings(rows), err
}

func (s *Service) ListForAddress(ctx context.Context, address string) ([]Binding, error) {
	rows, err := s.store.ListRoleBindingsForAddress(ctx, strings.ToLower(address))
	return toBindings(rows), err
}

// Bind is idempotent: binding an existing (address, role) pair returns the
// existing row.
func (s *Service) Bind(ctx context.Context, address, role string) (Binding, error) {
	if !strings.HasPrefix(address, "0x") {
		return Binding{}, apperr.BadRequest("address must be a hex wallet address")
	}
	supplyRole := db.SupplyRole(role)
	if !supplyRole.Valid() {
		return Binding{}, apperr.BadRequest("Unknown role: " + role)
	}
	row, err := s.store.UpsertRoleBinding(ctx, db.UpsertRoleBindingParams{
		ID:      cuid.New(),
		Address: strings.ToLower(address),
		Role:    supplyRole,
		Now:     jsonx.Now(),
	})
	if err != nil {
		return Binding{}, err
	}
	return toBinding(row), nil
}

func (s *Service) Unbind(ctx context.Context, address, role string) (Deleted, error) {
	notFound := apperr.NotFound("Role binding not found")
	supplyRole := db.SupplyRole(role)
	if !supplyRole.Valid() {
		return Deleted{}, notFound
	}
	n, err := s.store.DeleteRoleBinding(ctx, db.DeleteRoleBindingParams{
		Address: strings.ToLower(address),
		Role:    supplyRole,
	})
	if err != nil {
		return Deleted{}, err
	}
	if n == 0 {
		return Deleted{}, notFound
	}
	return Deleted{Deleted: true}, nil
}

func toBindings(rows []db.RoleBinding) []Binding {
	out := make([]Binding, 0, len(rows))
	for _, r := range rows {
		out = append(out, toBinding(r))
	}
	return out
}

func toBinding(r db.RoleBinding) Binding {
	return Binding{
		ID:        r.ID,
		Address:   r.Address,
		Role:      string(r.Role),
		CreatedAt: jsonx.ISOTime(r.CreatedAt),
		UpdatedAt: jsonx.ISOTime(r.UpdatedAt),
	}
}
