package chain

import (
	"context"
	"math/big"
	"strings"

	"github.com/ethereum/go-ethereum/common"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// ProductView is the on-chain state returned by getProductStatus.
type ProductView struct {
	Status       db.ProductStatus
	CurrentOwner string
	MetadataCID  string
}

// Reader serves public reads for products the indexer has not seen.
type Reader struct {
	contract *Contract
}

// NewReader returns nil when contract is nil, meaning chain reads are
// unavailable.
func NewReader(contract *Contract) *Reader {
	if contract == nil {
		return nil
	}
	return &Reader{contract: contract}
}

// ProductStatus reads a product by numeric id; ok is false when the call
// fails, including when the product does not exist.
func (r *Reader) ProductStatus(ctx context.Context, chainProductID string) (ProductView, bool) {
	if r == nil {
		return ProductView{}, false
	}
	id, valid := new(big.Int).SetString(chainProductID, 10)
	if !valid {
		return ProductView{}, false
	}
	out, err := r.contract.Call(ctx, "getProductStatus", id)
	if err != nil || len(out) < 3 {
		return ProductView{}, false
	}
	owner, _ := out[1].(common.Address)
	cid, _ := out[2].(string)
	return ProductView{
		Status:       StatusFromChain(out[0], db.ProductStatusInvalid),
		CurrentOwner: strings.ToLower(owner.Hex()),
		MetadataCID:  cid,
	}, true
}
