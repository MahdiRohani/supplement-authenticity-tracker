// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

import {AccessControl} from "@openzeppelin/contracts/access/AccessControl.sol";
import {ProductId, ProductStatus} from "./domain/ProductTypes.sol";

contract SupplementRegistry is AccessControl {
    bytes32 public constant MANUFACTURER_ROLE = keccak256("MANUFACTURER_ROLE");

    struct Product {
        address owner;
        ProductStatus status;
        bool exists;
    }

    ProductId private _nextProductId;
    mapping(ProductId => Product) private _products;

    error ProductDoesNotExist(ProductId productId);
    error InvalidBatchSize(uint256 count);

    constructor(address admin) {
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(MANUFACTURER_ROLE, admin);
    }

    function registerUnit()
        external
        onlyRole(MANUFACTURER_ROLE)
        returns (ProductId productId)
    {
        productId = _mintUnit(msg.sender);
    }

    function registerBatch(
        uint256 count
    ) external onlyRole(MANUFACTURER_ROLE) returns (ProductId firstProductId) {
        if (count == 0) {
            revert InvalidBatchSize(count);
        }

        firstProductId = ProductId.wrap(ProductId.unwrap(_nextProductId) + 1);
        for (uint256 i = 0; i < count; ) {
            _mintUnit(msg.sender);
            unchecked {
                ++i;
            }
        }
    }

    function getProduct(
        ProductId productId
    ) external view returns (address owner, ProductStatus status) {
        Product storage product = _products[productId];
        if (!product.exists) {
            revert ProductDoesNotExist(productId);
        }
        return (product.owner, product.status);
    }

    function nextProductId() external view returns (ProductId) {
        return _nextProductId;
    }

    function _mintUnit(address owner) internal returns (ProductId productId) {
        uint256 next = ProductId.unwrap(_nextProductId) + 1;
        productId = ProductId.wrap(next);
        _nextProductId = productId;
        _products[productId] = Product({
            owner: owner,
            status: ProductStatus.Created,
            exists: true
        });
    }
}
