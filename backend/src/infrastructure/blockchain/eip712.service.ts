import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { verifyTypedData, Wallet, TypedDataField } from 'ethers';

const METADATA_TYPES: Record<string, TypedDataField[]> = {
  ManufacturerMetadata: [
    { name: 'metadataHash', type: 'bytes32' },
    { name: 'manufacturer', type: 'address' },
    { name: 'chainId', type: 'uint256' },
    { name: 'nonce', type: 'uint256' },
  ],
};

const CONSUME_TYPES: Record<string, TypedDataField[]> = {
  ConsumeAuthorization: [
    { name: 'productId', type: 'uint256' },
    { name: 'secret', type: 'bytes32' },
    { name: 'consumer', type: 'address' },
    { name: 'deadline', type: 'uint256' },
  ],
};

@Injectable()
export class Eip712Service {
  constructor(private readonly config: ConfigService) {}

  private domain(chainId: number) {
    return {
      name: 'SupplementRegistry',
      version: '1',
      chainId,
      verifyingContract:
        this.config.get<string>('REGISTRY_ADDRESS') ||
        '0x0000000000000000000000000000000000000000',
    };
  }

  async signManufacturerMetadata(input: {
    privateKey: string;
    metadataHash: string;
    manufacturer: string;
    chainId: number;
    nonce: number;
  }) {
    const wallet = new Wallet(input.privateKey);
    return wallet.signTypedData(this.domain(input.chainId), METADATA_TYPES, {
      metadataHash: input.metadataHash,
      manufacturer: input.manufacturer,
      chainId: input.chainId,
      nonce: input.nonce,
    });
  }

  verifyManufacturerMetadata(input: {
    signature: string;
    metadataHash: string;
    manufacturer: string;
    chainId: number;
    nonce: number;
  }): string {
    return verifyTypedData(
      this.domain(input.chainId),
      METADATA_TYPES,
      {
        metadataHash: input.metadataHash,
        manufacturer: input.manufacturer,
        chainId: input.chainId,
        nonce: input.nonce,
      },
      input.signature,
    );
  }

  verifyConsumeAuthorization(input: {
    signature: string;
    productId: string;
    secret: string;
    consumer: string;
    deadline: number;
    chainId: number;
  }): string {
    return verifyTypedData(
      this.domain(input.chainId),
      CONSUME_TYPES,
      {
        productId: BigInt(input.productId),
        secret: input.secret,
        consumer: input.consumer,
        deadline: BigInt(input.deadline),
      },
      input.signature,
    );
  }
}
