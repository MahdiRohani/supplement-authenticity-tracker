package chain

import (
	"context"
	"crypto/ecdsa"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"sync"
	"time"

	"github.com/ethereum/go-ethereum"
	"github.com/ethereum/go-ethereum/accounts/abi"
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/core/types"
	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/ethclient"
	"github.com/ethereum/go-ethereum/rpc"
)

// Backend is the subset of ethclient.Client the contract wrapper needs.
type Backend interface {
	ChainID(ctx context.Context) (*big.Int, error)
	BlockNumber(ctx context.Context) (uint64, error)
	HeaderByNumber(ctx context.Context, number *big.Int) (*types.Header, error)
	CallContract(ctx context.Context, msg ethereum.CallMsg, blockNumber *big.Int) ([]byte, error)
	EstimateGas(ctx context.Context, msg ethereum.CallMsg) (uint64, error)
	SuggestGasPrice(ctx context.Context) (*big.Int, error)
	SuggestGasTipCap(ctx context.Context) (*big.Int, error)
	FilterLogs(ctx context.Context, q ethereum.FilterQuery) ([]types.Log, error)
	PendingNonceAt(ctx context.Context, account common.Address) (uint64, error)
	SendTransaction(ctx context.Context, tx *types.Transaction) error
	TransactionReceipt(ctx context.Context, txHash common.Hash) (*types.Receipt, error)
}

var _ Backend = (*ethclient.Client)(nil)

// RevertError is a contract call that reverted. Name is the decoded custom
// error (e.g. "ProductAlreadyConsumed") when the ABI knows it.
type RevertError struct {
	Name string
	Err  error
}

func (e *RevertError) Error() string {
	if e.Name != "" {
		return fmt.Sprintf("execution reverted: %s: %v", e.Name, e.Err)
	}
	return fmt.Sprintf("execution reverted: %v", e.Err)
}

func (e *RevertError) Unwrap() error { return e.Err }

// Contract binds the registry ABI to an address using runtime ABI decoding, so
// an updated artifact only needs a restart, not code generation.
type Contract struct {
	backend Backend
	abi     abi.ABI
	address common.Address

	chainIDMu sync.Mutex
	chainID   *big.Int

	sendLocks sync.Map // common.Address -> *sync.Mutex
}

func NewContract(backend Backend, parsed abi.ABI, address common.Address) *Contract {
	return &Contract{backend: backend, abi: parsed, address: address}
}

func (c *Contract) Address() common.Address { return c.address }

func (c *Contract) ABI() *abi.ABI { return &c.abi }

// Call executes a view method and returns its outputs in declaration order.
func (c *Contract) Call(ctx context.Context, method string, args ...any) ([]any, error) {
	data, err := c.abi.Pack(method, args...)
	if err != nil {
		return nil, fmt.Errorf("pack %s: %w", method, err)
	}
	out, err := c.backend.CallContract(ctx, ethereum.CallMsg{To: &c.address, Data: data}, nil)
	if err != nil {
		return nil, c.decodeRevert(err)
	}
	values, err := c.abi.Unpack(method, out)
	if err != nil {
		return nil, fmt.Errorf("unpack %s: %w", method, err)
	}
	return values, nil
}

// Transact signs and submits a state-changing call, then waits for it to be
// mined. Gas is estimated first so reverts surface with their custom error.
func (c *Contract) Transact(ctx context.Context, key *ecdsa.PrivateKey, method string, args ...any) (*types.Receipt, error) {
	data, err := c.abi.Pack(method, args...)
	if err != nil {
		return nil, fmt.Errorf("pack %s: %w", method, err)
	}
	signed, err := c.send(ctx, key, data)
	if err != nil {
		return nil, err
	}
	receipt, err := c.waitMined(ctx, signed.Hash())
	if err != nil {
		return nil, err
	}
	if receipt.Status != types.ReceiptStatusSuccessful {
		return receipt, &RevertError{Err: fmt.Errorf("transaction %s reverted", signed.Hash().Hex())}
	}
	return receipt, nil
}

// send holds a per-sender lock from nonce lookup until the node accepts the
// transaction, so concurrent requests using the same key get distinct nonces.
func (c *Contract) send(ctx context.Context, key *ecdsa.PrivateKey, data []byte) (*types.Transaction, error) {
	from := crypto.PubkeyToAddress(key.PublicKey)
	lock, _ := c.sendLocks.LoadOrStore(from, &sync.Mutex{})
	mu := lock.(*sync.Mutex)
	mu.Lock()
	defer mu.Unlock()

	chainID, err := c.ChainID(ctx)
	if err != nil {
		return nil, err
	}
	gas, err := c.backend.EstimateGas(ctx, ethereum.CallMsg{From: from, To: &c.address, Data: data})
	if err != nil {
		return nil, c.decodeRevert(err)
	}
	nonce, err := c.backend.PendingNonceAt(ctx, from)
	if err != nil {
		return nil, fmt.Errorf("pending nonce: %w", err)
	}
	head, err := c.backend.HeaderByNumber(ctx, nil)
	if err != nil {
		return nil, fmt.Errorf("latest header: %w", err)
	}

	var tx *types.Transaction
	if head.BaseFee != nil {
		tip, err := c.backend.SuggestGasTipCap(ctx)
		if err != nil {
			return nil, fmt.Errorf("suggest tip: %w", err)
		}
		feeCap := new(big.Int).Add(tip, new(big.Int).Mul(head.BaseFee, big.NewInt(2)))
		tx = types.NewTx(&types.DynamicFeeTx{
			ChainID: chainID, Nonce: nonce, GasTipCap: tip, GasFeeCap: feeCap,
			Gas: gas, To: &c.address, Data: data,
		})
	} else {
		price, err := c.backend.SuggestGasPrice(ctx)
		if err != nil {
			return nil, fmt.Errorf("suggest gas price: %w", err)
		}
		tx = types.NewTx(&types.LegacyTx{Nonce: nonce, GasPrice: price, Gas: gas, To: &c.address, Data: data})
	}

	signed, err := types.SignTx(tx, types.LatestSignerForChainID(chainID), key)
	if err != nil {
		return nil, fmt.Errorf("sign transaction: %w", err)
	}
	if err := c.backend.SendTransaction(ctx, signed); err != nil {
		return nil, c.decodeRevert(err)
	}
	return signed, nil
}

func (c *Contract) waitMined(ctx context.Context, hash common.Hash) (*types.Receipt, error) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		receipt, err := c.backend.TransactionReceipt(ctx, hash)
		if err == nil {
			return receipt, nil
		}
		if !errors.Is(err, ethereum.NotFound) {
			return nil, fmt.Errorf("receipt %s: %w", hash.Hex(), err)
		}
		select {
		case <-ctx.Done():
			return nil, fmt.Errorf("waiting for %s: %w", hash.Hex(), ctx.Err())
		case <-ticker.C:
		}
	}
}

func (c *Contract) ChainID(ctx context.Context) (*big.Int, error) {
	c.chainIDMu.Lock()
	defer c.chainIDMu.Unlock()
	if c.chainID != nil {
		return c.chainID, nil
	}
	id, err := c.backend.ChainID(ctx)
	if err != nil {
		return nil, fmt.Errorf("chain id: %w", err)
	}
	c.chainID = id
	return id, nil
}

// ParseEvent decodes a log emitted by the named event (indexed and data
// fields). ok is false when the log belongs to a different event.
func (c *Contract) ParseEvent(name string, log types.Log) (map[string]any, bool, error) {
	event, found := c.abi.Events[name]
	if !found || len(log.Topics) == 0 || log.Topics[0] != event.ID {
		return nil, false, nil
	}
	out := map[string]any{}
	if len(log.Data) > 0 {
		if err := event.Inputs.NonIndexed().UnpackIntoMap(out, log.Data); err != nil {
			return nil, true, fmt.Errorf("unpack %s data: %w", name, err)
		}
	}
	var indexed abi.Arguments
	for _, arg := range event.Inputs {
		if arg.Indexed {
			indexed = append(indexed, arg)
		}
	}
	if err := abi.ParseTopicsIntoMap(out, indexed, log.Topics[1:]); err != nil {
		return nil, true, fmt.Errorf("unpack %s topics: %w", name, err)
	}
	return out, true, nil
}

func (c *Contract) EventID(name string) common.Hash {
	return c.abi.Events[name].ID
}

// decodeRevert turns a JSON-RPC revert into a RevertError naming the custom
// error. Nodes differ in where they put revert data (geth: hex string,
// Hardhat: object with a "data" field), and Hardhat also spells the error in
// the message, which is used as a fallback.
func (c *Contract) decodeRevert(err error) error {
	var dataErr rpc.DataError
	if errors.As(err, &dataErr) {
		if name := c.errorNameFromData(dataErr.ErrorData()); name != "" {
			return &RevertError{Name: name, Err: err}
		}
	}
	msg := err.Error()
	for name := range c.abi.Errors {
		if strings.Contains(msg, name) {
			return &RevertError{Name: name, Err: err}
		}
	}
	if strings.Contains(msg, "revert") {
		return &RevertError{Err: err}
	}
	return err
}

func (c *Contract) errorNameFromData(data any) string {
	var hexData string
	switch v := data.(type) {
	case string:
		hexData = v
	case map[string]any:
		if s, ok := v["data"].(string); ok {
			hexData = s
		}
	}
	raw, err := hexutil.Decode(hexData)
	if err != nil || len(raw) < 4 {
		return ""
	}
	var selector [4]byte
	copy(selector[:], raw[:4])
	abiErr, err := c.abi.ErrorByID(selector)
	if err != nil {
		return ""
	}
	return abiErr.Name
}
