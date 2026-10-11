package roles

import (
	"context"
	"fmt"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/ethereum/go-ethereum/common"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

type ProfileStore interface {
	Store
	UpsertRoleBindingProfile(ctx context.Context, arg db.UpsertRoleBindingProfileParams) (db.RoleBinding, error)
}

// Party is a v2 role binding with the public profile shown to buyers who
// scan a unit held by this wallet.
type Party struct {
	ID          string  `json:"id"`
	Address     string  `json:"address"`
	Role        string  `json:"role"`
	DisplayName *string `json:"displayName"`
	Region      *string `json:"region"`
	CreatedAt   string  `json:"createdAt"`
	UpdatedAt   string  `json:"updatedAt"`
}

// RoleGranter grants the matching role on SupplementRegistryV2, which only
// lets custody move to wallets holding the next role.
type RoleGranter interface {
	GrantRole(ctx context.Context, role, account string) (chain.RoleGrant, error)
}

type PartyService struct {
	store   ProfileStore
	granter RoleGranter
}

// NewPartyService builds the v2 role service; granter may be nil, in which
// case bindings stay off-chain.
func NewPartyService(st ProfileStore, granter RoleGranter) *PartyService {
	return &PartyService{store: st, granter: granter}
}

type BindInput struct {
	Address     string
	Role        string
	DisplayName *string
	Region      *string
	// OnChain also grants the role on the registry (default true).
	OnChain *bool
}

type BoundParty struct {
	Party
	OnChain *chain.RoleGrant `json:"onChain"`
}

func (s *PartyService) List(ctx context.Context, address string) ([]Party, error) {
	var rows []db.RoleBinding
	var err error
	if address = strings.TrimSpace(address); address != "" {
		rows, err = s.store.ListRoleBindingsForAddress(ctx, strings.ToLower(address))
	} else {
		rows, err = s.store.ListRoleBindings(ctx)
	}
	if err != nil {
		return nil, err
	}
	out := make([]Party, 0, len(rows))
	for _, r := range rows {
		out = append(out, toParty(r))
	}
	return out, nil
}

// Bind is idempotent; on a repeated bind only the provided profile fields
// change. The on-chain grant runs first so a failed grant leaves no
// off-chain binding that the registry would not honour.
func (s *PartyService) Bind(ctx context.Context, in BindInput) (BoundParty, error) {
	address := strings.ToLower(strings.TrimSpace(in.Address))
	if !common.IsHexAddress(address) {
		return BoundParty{}, apperr.BadRequest("address must be a hex wallet address")
	}
	supplyRole := db.SupplyRole(in.Role)
	if !supplyRole.Valid() {
		return BoundParty{}, apperr.BadRequest("Unknown role: " + in.Role)
	}
	displayName, region := in.DisplayName, in.Region
	if displayName != nil {
		name := strings.TrimSpace(*displayName)
		if name == "" || utf8.RuneCountInString(name) > 120 {
			return BoundParty{}, apperr.BadRequest("displayName must be 1-120 characters")
		}
		displayName = &name
	}
	if region != nil {
		if region = normalizeRegion(*region); region == nil {
			return BoundParty{}, apperr.BadRequest("region must contain letters or digits")
		}
	}

	var grant *chain.RoleGrant
	if in.OnChain == nil || *in.OnChain {
		if s.granter == nil {
			return BoundParty{}, apperr.ServiceUnavailable("On-chain role grants are not configured; pass onChain: false to bind off-chain only")
		}
		g, err := s.granter.GrantRole(ctx, string(supplyRole), address)
		if err != nil {
			return BoundParty{}, err
		}
		grant = &g
	}

	row, err := s.store.UpsertRoleBindingProfile(ctx, db.UpsertRoleBindingProfileParams{
		ID:          cuid.New(),
		Address:     address,
		Role:        supplyRole,
		DisplayName: displayName,
		Region:      region,
		Now:         jsonx.Now(),
	})
	if err != nil {
		return BoundParty{}, err
	}
	return BoundParty{Party: toParty(row), OnChain: grant}, nil
}

// Resync grants the on-chain role of every stored supply-chain binding after
// the indexer finds a fresh chain (a restarted dev node), where all earlier
// grants are gone. GrantRole skips roles already held. Admin bindings are
// left out so the registry admin role is only ever granted explicitly.
// Bindings made with onChain:false are granted too: the table does not
// record that choice, and resets only happen outside production.
func (s *PartyService) Resync(ctx context.Context) error {
	if s.granter == nil {
		return nil
	}
	rows, err := s.store.ListRoleBindings(ctx)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if r.Role == db.SupplyRoleAdmin {
			continue
		}
		if _, err := s.granter.GrantRole(ctx, string(r.Role), r.Address); err != nil {
			return fmt.Errorf("grant %s to %s: %w", r.Role, r.Address, err)
		}
	}
	return nil
}

func toParty(r db.RoleBinding) Party {
	return Party{
		ID:          r.ID,
		Address:     r.Address,
		Role:        string(r.Role),
		DisplayName: r.DisplayName,
		Region:      r.Region,
		CreatedAt:   jsonx.ISOTime(r.CreatedAt),
		UpdatedAt:   jsonx.ISOTime(r.UpdatedAt),
	}
}

// normalizeRegion folds a coarse region name (city or province) into the
// lowercase slug the Android app maps to a localized label; nil when empty.
func normalizeRegion(raw string) *string {
	region := strings.Map(func(r rune) rune {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r) || r == '-':
			return r
		case unicode.IsSpace(r) || r == '_':
			return '-'
		default:
			return -1
		}
	}, strings.ToLower(strings.TrimSpace(raw)))
	if region == "" {
		return nil
	}
	if r := []rune(region); len(r) > 64 {
		region = string(r[:64])
	}
	return &region
}
