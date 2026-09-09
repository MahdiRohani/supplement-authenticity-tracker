import {
  ProductRegistered,
  OwnershipTransferred,
  ProductConsumed,
  ProductInvalidated,
} from "../generated/SupplementRegistry/SupplementRegistry";
import { OwnershipTransfer, Product } from "../generated/schema";

export function handleProductRegistered(event: ProductRegistered): void {
  let product = new Product(event.params.productId.toString());
  product.owner = event.params.manufacturer;
  product.status = event.params.status;
  product.metadataCid = event.params.metadataCid;
  product.metadataHash = event.params.metadataHash;
  product.updatedAtBlock = event.block.number;
  product.save();
}

export function handleOwnershipTransferred(event: OwnershipTransferred): void {
  let product = Product.load(event.params.productId.toString());
  if (product == null) {
    product = new Product(event.params.productId.toString());
    product.status = 1;
    product.metadataCid = null;
    product.metadataHash = null;
  }
  product.owner = event.params.to;
  product.updatedAtBlock = event.block.number;
  product.save();

  let transfer = new OwnershipTransfer(
    event.transaction.hash.toHex() + "-" + event.logIndex.toString()
  );
  transfer.product = product.id;
  transfer.from = event.params.from;
  transfer.to = event.params.to;
  transfer.txHash = event.transaction.hash;
  transfer.blockNumber = event.block.number;
  transfer.save();
}

export function handleProductConsumed(event: ProductConsumed): void {
  let product = Product.load(event.params.productId.toString());
  if (product == null) {
    product = new Product(event.params.productId.toString());
    product.owner = event.params.actor;
    product.metadataCid = null;
    product.metadataHash = null;
  }
  product.status = 3;
  product.updatedAtBlock = event.block.number;
  product.save();
}

export function handleProductInvalidated(event: ProductInvalidated): void {
  let product = Product.load(event.params.productId.toString());
  if (product == null) {
    product = new Product(event.params.productId.toString());
    product.owner = event.params.actor;
    product.metadataCid = null;
    product.metadataHash = null;
  }
  product.status = 4;
  product.updatedAtBlock = event.block.number;
  product.save();
}
