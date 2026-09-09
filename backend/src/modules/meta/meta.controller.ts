import { Body, Controller, Post } from '@nestjs/common';
import {
  BadRequestException,
  ServiceUnavailableException,
} from '@nestjs/common';
import { IsInt, IsOptional, IsString, Min, MinLength } from 'class-validator';
import { ChainConfigService } from '../../config/chain-config.service';
import { FeatureFlagsService } from '../../config/feature-flags.service';
import { Eip712Service } from '../../infrastructure/blockchain/eip712.service';
import { ProductsService } from '../products/products.service';

class SignMetadataDto {
  @IsString()
  @MinLength(66)
  metadataHash!: string;

  @IsString()
  @MinLength(42)
  manufacturer!: string;

  @IsString()
  @MinLength(66)
  privateKey!: string;

  @IsOptional()
  @IsInt()
  @Min(0)
  nonce?: number;

  @IsOptional()
  @IsInt()
  chainId?: number;
}

class VerifyMetadataDto {
  @IsString()
  @MinLength(66)
  metadataHash!: string;

  @IsString()
  @MinLength(42)
  manufacturer!: string;

  @IsString()
  @MinLength(10)
  signature!: string;

  @IsInt()
  @Min(0)
  nonce!: number;

  @IsOptional()
  @IsInt()
  chainId?: number;
}

class MetaTxConsumeDto {
  @IsString()
  @MinLength(1)
  productId!: string;

  @IsString()
  @MinLength(66)
  secret!: string;

  @IsString()
  @MinLength(42)
  consumer!: string;

  @IsInt()
  @Min(1)
  deadline!: number;

  @IsString()
  @MinLength(10)
  signature!: string;
}

@Controller('meta')
export class MetaController {
  constructor(
    private readonly eip712: Eip712Service,
    private readonly flags: FeatureFlagsService,
    private readonly chain: ChainConfigService,
    private readonly products: ProductsService,
  ) {}

  @Post('eip712/metadata/sign')
  async signMetadata(@Body() body: SignMetadataDto) {
    if (!this.flags.getFlags().eip712MetadataEnabled) {
      throw new ServiceUnavailableException('EIP-712 metadata signing disabled');
    }
    const chainId = body.chainId ?? this.chain.getChainId();
    const nonce = body.nonce ?? 0;
    const signature = await this.eip712.signManufacturerMetadata({
      privateKey: body.privateKey,
      metadataHash: body.metadataHash,
      manufacturer: body.manufacturer,
      chainId,
      nonce,
    });
    return { signature, chainId, nonce };
  }

  @Post('eip712/metadata/verify')
  verifyMetadata(@Body() body: VerifyMetadataDto) {
    if (!this.flags.getFlags().eip712MetadataEnabled) {
      throw new ServiceUnavailableException('EIP-712 metadata verify disabled');
    }
    const chainId = body.chainId ?? this.chain.getChainId();
    const recovered = this.eip712.verifyManufacturerMetadata({
      signature: body.signature,
      metadataHash: body.metadataHash,
      manufacturer: body.manufacturer,
      chainId,
      nonce: body.nonce,
    });
    const ok = recovered.toLowerCase() === body.manufacturer.toLowerCase();
    return { valid: ok, recovered, chainId };
  }

  @Post('consume')
  async metaTxConsume(@Body() body: MetaTxConsumeDto) {
    if (!this.flags.getFlags().metaTxConsumeEnabled) {
      throw new ServiceUnavailableException('Meta-tx consume disabled');
    }
    if (body.deadline < Math.floor(Date.now() / 1000)) {
      throw new BadRequestException('Authorization deadline expired');
    }
    const chainId = this.chain.getChainId();
    const recovered = this.eip712.verifyConsumeAuthorization({
      signature: body.signature,
      productId: body.productId,
      secret: body.secret,
      consumer: body.consumer,
      deadline: body.deadline,
      chainId,
    });
    if (recovered.toLowerCase() !== body.consumer.toLowerCase()) {
      throw new BadRequestException('Invalid consume authorization signature');
    }
    return this.products.consumeProduct(body.productId, body.secret);
  }
}
