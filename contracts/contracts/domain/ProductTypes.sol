// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

enum ProductStatus {
    Created,
    Transferred,
    AtPointOfSale,
    Consumed,
    Invalid
}

type ProductId is uint256;
