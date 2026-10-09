package chain

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"math/big"
	"testing"

	"github.com/ethereum/go-ethereum"
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/core/types"
	"github.com/jackc/pgx/v5"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// devChain is an empty chain whose block hashes depend on generation, so
// bumping it mimics a restarted Hardhat node.
type devChain struct {
	Backend
	generation byte
	latest     uint64
}

func (c *devChain) BlockNumber(context.Context) (uint64, error) { return c.latest, nil }

func (c *devChain) HeaderByNumber(_ context.Context, n *big.Int) (*types.Header, error) {
	if n.Uint64() > c.latest {
		return nil, ethereum.NotFound
	}
	return &types.Header{Number: new(big.Int).Set(n), Extra: []byte{c.generation}}, nil
}

func (c *devChain) FilterLogs(context.Context, ethereum.FilterQuery) ([]types.Log, error) {
	return nil, nil
}

type memCursors struct {
	cursor *db.IndexerCursor
	resets int
}

func (m *memCursors) GetIndexerCursor(context.Context, string) (db.IndexerCursor, error) {
	if m.cursor == nil {
		return db.IndexerCursor{}, pgx.ErrNoRows
	}
	return *m.cursor, nil
}

func (m *memCursors) SetIndexerCursor(_ context.Context, arg db.SetIndexerCursorParams) error {
	m.cursor = &db.IndexerCursor{Name: arg.Name, LastBlock: arg.LastBlock, BlockHash: arg.BlockHash}
	return nil
}

func (m *memCursors) ResetV2Projection(context.Context, string) error {
	m.cursor = nil
	m.resets++
	return nil
}

func newTestIndexer(t *testing.T, backend Backend, cursors CursorStore, opts IndexerV2Options) *IndexerV2 {
	t.Helper()
	artifact, err := LoadArtifact("../../../packages/abis/SupplementRegistryV2.json")
	if err != nil {
		t.Fatal(err)
	}
	contract := NewContract(backend, artifact.ABI, common.HexToAddress("0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512"))
	return NewIndexerV2(backend, contract, nil, cursors, slog.New(slog.NewTextHandler(io.Discard, nil)), opts)
}

func TestIndexerV2ResetsWhenTheDevChainRestartsUnderARunningAPI(t *testing.T) {
	ctx := context.Background()
	node := &devChain{generation: 1, latest: 5}
	cursors := &memCursors{}
	hookCalls, hookErr := 0, errors.New("registry not deployed yet")
	ix := newTestIndexer(t, node, cursors, IndexerV2Options{
		AllowReset: true,
		OnReset: func(context.Context) error {
			hookCalls++
			return hookErr
		},
	})

	if err := ix.Poll(ctx); err != nil {
		t.Fatal(err)
	}
	if cursors.cursor == nil || cursors.cursor.LastBlock != 5 || hookCalls != 0 {
		t.Fatalf("first run: cursor %+v, hook calls %d", cursors.cursor, hookCalls)
	}

	// Restarted node, shorter than the cursor.
	node.generation, node.latest = 2, 2
	if err := ix.Poll(ctx); err != nil {
		t.Fatal(err)
	}
	if cursors.resets != 1 || cursors.cursor.LastBlock != 2 || hookCalls != 1 {
		t.Fatalf("after restart: resets %d, cursor %+v, hook calls %d", cursors.resets, cursors.cursor, hookCalls)
	}

	// The hook failed, so it runs again; once it succeeds it stops.
	hookErr = nil
	for range 2 {
		if err := ix.Poll(ctx); err != nil {
			t.Fatal(err)
		}
	}
	if hookCalls != 2 || cursors.resets != 1 {
		t.Fatalf("hook retry: calls %d, resets %d", hookCalls, cursors.resets)
	}

	// Restarted again and already at the same height: only the hash differs.
	node.generation = 3
	if err := ix.Poll(ctx); err != nil {
		t.Fatal(err)
	}
	if cursors.resets != 2 || hookCalls != 3 {
		t.Fatalf("same-height restart: resets %d, hook calls %d", cursors.resets, hookCalls)
	}
}

func TestIndexerV2HaltsOnAChainChangeWhenResetIsNotAllowed(t *testing.T) {
	ctx := context.Background()
	node := &devChain{generation: 1, latest: 3}
	cursors := &memCursors{}
	ix := newTestIndexer(t, node, cursors, IndexerV2Options{})
	if err := ix.Poll(ctx); err != nil {
		t.Fatal(err)
	}
	node.generation = 2
	if err := ix.Poll(ctx); err == nil || cursors.resets != 0 {
		t.Fatalf("production indexer must halt instead of resetting: %v, resets %d", err, cursors.resets)
	}
	if err := ix.Poll(ctx); !errors.Is(err, errIndexerHalted) {
		t.Fatalf("halted indexer polled again: %v", err)
	}
}
