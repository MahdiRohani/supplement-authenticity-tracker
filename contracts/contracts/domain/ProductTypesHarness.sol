// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

import {ProductStatus, ProductId} from "./ProductTypes.sol";

contract ProductTypesHarness {
    function statusCreated() external pure returns (ProductStatus) {
        return ProductStatus.Created;
    }

    function statusTransferred() external pure returns (ProductStatus) {
        return ProductStatus.Transferred;
    }

    function statusAtPointOfSale() external pure returns (ProductStatus) {
        return ProductStatus.AtPointOfSale;
    }

    function statusConsumed() external pure returns (ProductStatus) {
        return ProductStatus.Consumed;
    }

    function statusInvalid() external pure returns (ProductStatus) {
        return ProductStatus.Invalid;
    }

    function wrapId(uint256 raw) external pure returns (ProductId) {
        return ProductId.wrap(raw);
    }

    function unwrapId(ProductId id) external pure returns (uint256) {
        return ProductId.unwrap(id);
    }
}
