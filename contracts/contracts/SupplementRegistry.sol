// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

import {AccessControl} from "@openzeppelin/contracts/access/AccessControl.sol";
import {ProductId, ProductStatus} from "./domain/ProductTypes.sol";

contract SupplementRegistry is AccessControl {
    bytes32 public constant MANUFACTURER_ROLE = keccak256("MANUFACTURER_ROLE");

    struct Product {
        address owner;
        ProductStatus status;
        bytes32 secretHash;
        string metadataCid;
        bytes32 metadataHash;
        bool exists;
    }

    ProductId private _nextProductId;
    mapping(ProductId => Product) private _products;

    event ProductRegistered(
        ProductId indexed productId,
        address indexed manufacturer,
        ProductStatus status,
        string metadataCid,
        bytes32 metadataHash
    );
    event OwnershipTransferred(
        ProductId indexed productId,
        address indexed from,
        address indexed to
    );
    event ProductConsumed(ProductId indexed productId, address indexed actor);
    event ProductInvalidated(
        ProductId indexed productId,
        address indexed actor
    );

    error ProductDoesNotExist(ProductId productId);
    error InvalidBatchSize(uint256 count);
    error InvalidSecretHash();
    error InvalidMetadataCid();
    error InvalidMetadataHash();
    error InvalidSecret(ProductId productId);
    error ProductAlreadyConsumed(ProductId productId);
    error ProductNotConsumable(ProductId productId, ProductStatus status);

    constructor(address admin) {
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(MANUFACTURER_ROLE, admin);
    }

    function registerUnit(
        bytes32 secretHash,
        string calldata metadataCid,
        bytes32 metadataHash
    ) external onlyRole(MANUFACTURER_ROLE) returns (ProductId productId) {
        productId = _mintUnit(msg.sender, secretHash, metadataCid, metadataHash);
    }

    function registerBatch(
        bytes32[] calldata secretHashes,
        string calldata metadataCid,
        bytes32 metadataHash
    ) external onlyRole(MANUFACTURER_ROLE) returns (ProductId firstProductId) {
        uint256 count = secretHashes.length;
        if (count == 0) {
            revert InvalidBatchSize(count);
        }

        firstProductId = ProductId.wrap(ProductId.unwrap(_nextProductId) + 1);
        for (uint256 i = 0; i < count; ) {
            _mintUnit(msg.sender, secretHashes[i], metadataCid, metadataHash);
            unchecked {
                ++i;
            }
        }
    }

    function consume(ProductId productId, bytes32 secret) external {
        Product storage product = _products[productId];
        if (!product.exists) {
            revert ProductDoesNotExist(productId);
        }
        if (product.status == ProductStatus.Consumed) {
            revert ProductAlreadyConsumed(productId);
        }
        if (
            product.status == ProductStatus.Invalid ||
            product.status == ProductStatus.Transferred ||
            product.status == ProductStatus.AtPointOfSale
        ) {
            revert ProductNotConsumable(productId, product.status);
        }
        if (keccak256(abi.encodePacked(secret)) != product.secretHash) {
            revert InvalidSecret(productId);
        }

        product.status = ProductStatus.Consumed;
        emit ProductConsumed(productId, msg.sender);
    }

    function getProduct(
        ProductId productId
    )
        external
        view
        returns (
            address owner,
            ProductStatus status,
            string memory metadataCid,
            bytes32 metadataHash
        )
    {
        Product storage product = _products[productId];
        if (!product.exists) {
            revert ProductDoesNotExist(productId);
        }
        return (
            product.owner,
            product.status,
            product.metadataCid,
            product.metadataHash
        );
    }

    function nextProductId() external view returns (ProductId) {
        return _nextProductId;
    }

    function _mintUnit(
        address owner,
        bytes32 secretHash,
        string calldata metadataCid,
        bytes32 metadataHash
    ) internal returns (ProductId productId) {
        if (secretHash == bytes32(0)) {
            revert InvalidSecretHash();
        }
        if (bytes(metadataCid).length == 0) {
            revert InvalidMetadataCid();
        }
        if (metadataHash == bytes32(0)) {
            revert InvalidMetadataHash();
        }

        uint256 next = ProductId.unwrap(_nextProductId) + 1;
        productId = ProductId.wrap(next);
        _nextProductId = productId;
        _products[productId] = Product({
            owner: owner,
            status: ProductStatus.Created,
            secretHash: secretHash,
            metadataCid: metadataCid,
            metadataHash: metadataHash,
            exists: true
        });
        emit ProductRegistered(
            productId,
            owner,
            ProductStatus.Created,
            metadataCid,
            metadataHash
        );
    }
}
