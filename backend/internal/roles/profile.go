package roles

import (
	"context"
	"strings"
	"unicode/utf8"

	"github.com/ethereum/go-ethereum/common"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/risk"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

type ProfileStore interface {
	Store
	UpsertRoleBindingProfile(ctx context.Context, arg db.UpsertRoleBindingProfileParams) (db.RoleBinding, error)
}

// Party is a v2 role binding with the public profile shown to buyers who
// scan a unit held by this wallet. Region also anchors the clone-detection
// rule that flags scans far from the custodian.
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
		if region = risk.NormalizeRegion(*region); region == nil {
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
