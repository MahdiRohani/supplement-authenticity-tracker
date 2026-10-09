package roles

import (
	"context"
	"errors"
	"testing"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

type profileStore struct {
	upserts  []db.UpsertRoleBindingProfileParams
	bindings []db.RoleBinding
}

func (s *profileStore) ListRoleBindings(context.Context) ([]db.RoleBinding, error) {
	return s.bindings, nil
}

func (*profileStore) ListRoleBindingsForAddress(context.Context, string) ([]db.RoleBinding, error) {
	return nil, nil
}

func (*profileStore) UpsertRoleBinding(context.Context, db.UpsertRoleBindingParams) (db.RoleBinding, error) {
	return db.RoleBinding{}, nil
}

func (*profileStore) DeleteRoleBinding(context.Context, db.DeleteRoleBindingParams) (int64, error) {
	return 0, nil
}

func (s *profileStore) UpsertRoleBindingProfile(_ context.Context, arg db.UpsertRoleBindingProfileParams) (db.RoleBinding, error) {
	s.upserts = append(s.upserts, arg)
	return db.RoleBinding{ID: arg.ID, Address: arg.Address, Role: arg.Role, DisplayName: arg.DisplayName, Region: arg.Region}, nil
}

type granter struct {
	calls [][2]string
	err   error
}

func (g *granter) GrantRole(_ context.Context, role, account string) (chain.RoleGrant, error) {
	g.calls = append(g.calls, [2]string{role, account})
	if g.err != nil {
		return chain.RoleGrant{}, g.err
	}
	return chain.RoleGrant{Role: role, TxHash: new("0x01")}, nil
}

const wallet = "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC"

func TestBindGrantsOnChainBeforeStoringTheProfile(t *testing.T) {
	st, g := &profileStore{}, &granter{}
	svc := NewPartyService(st, g)
	out, err := svc.Bind(context.Background(), BindInput{
		Address: " " + wallet + " ", Role: "Pharmacy", DisplayName: new("  Darou  "), Region: new("Tehran Province"),
	})
	if err != nil {
		t.Fatal(err)
	}
	lower := "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc"
	if len(g.calls) != 1 || g.calls[0] != [2]string{"Pharmacy", lower} {
		t.Fatalf("grants = %v", g.calls)
	}
	if out.OnChain == nil || *out.OnChain.TxHash != "0x01" || out.Address != lower || *out.DisplayName != "Darou" {
		t.Fatalf("bound = %+v", out)
	}
	if got := *st.upserts[0].Region; got != "tehran-province" {
		t.Fatalf("region = %q", got)
	}
}

func TestResyncRegrantsSupplyRolesButNotAdmin(t *testing.T) {
	ctx := context.Background()
	st := &profileStore{bindings: []db.RoleBinding{
		{Address: "0xd1", Role: db.SupplyRoleDistributor},
		{Address: "0xa1", Role: db.SupplyRoleAdmin},
		{Address: "0xp1", Role: db.SupplyRolePharmacy},
	}}
	g := &granter{}
	if err := NewPartyService(st, g).Resync(ctx); err != nil {
		t.Fatal(err)
	}
	want := [][2]string{{"Distributor", "0xd1"}, {"Pharmacy", "0xp1"}}
	if len(g.calls) != len(want) || g.calls[0] != want[0] || g.calls[1] != want[1] {
		t.Fatalf("grants = %v", g.calls)
	}

	if err := NewPartyService(st, &granter{err: errors.New("registry not deployed")}).Resync(ctx); err == nil {
		t.Fatal("a failed grant must be reported so the indexer retries")
	}
	if err := NewPartyService(st, nil).Resync(ctx); err != nil {
		t.Fatalf("no granter: %v", err)
	}
}

func TestBindOffChainAndFailures(t *testing.T) {
	ctx := context.Background()
	off := false

	st := &profileStore{}
	out, err := NewPartyService(st, nil).Bind(ctx, BindInput{Address: wallet, Role: "Distributor", OnChain: &off})
	if err != nil || out.OnChain != nil || len(st.upserts) != 1 {
		t.Fatalf("off-chain bind = %+v, %v", out, err)
	}

	st = &profileStore{}
	_, err = NewPartyService(st, nil).Bind(ctx, BindInput{Address: wallet, Role: "Distributor"})
	var ae *apperr.Error
	if !errors.As(err, &ae) || ae.Status != 503 || len(st.upserts) != 0 {
		t.Fatalf("on-chain bind without a granter = %v", err)
	}

	st = &profileStore{}
	g := &granter{err: apperr.ServiceUnavailable("no admin key")}
	if _, err := NewPartyService(st, g).Bind(ctx, BindInput{Address: wallet, Role: "Pharmacy"}); err == nil || len(st.upserts) != 0 {
		t.Fatalf("a failed grant must not store the binding: %v %v", err, st.upserts)
	}

	for _, in := range []BindInput{
		{Address: "0x123", Role: "Pharmacy"},
		{Address: wallet, Role: "Wholesaler"},
		{Address: wallet, Role: "Pharmacy", DisplayName: new("   ")},
		{Address: wallet, Role: "Pharmacy", Region: new("!!!")},
	} {
		in.OnChain = &off
		_, err := NewPartyService(&profileStore{}, nil).Bind(ctx, in)
		if !errors.As(err, &ae) || ae.Status != 400 {
			t.Errorf("%+v: %v", in, err)
		}
	}
}
