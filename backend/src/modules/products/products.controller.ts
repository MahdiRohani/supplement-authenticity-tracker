import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Param,
  Post,
  Query,
} from '@nestjs/common';
import { ProductsService } from './products.service';

type RegisterProductBody = {
  name: string;
  batch?: string;
  manufacturerAddress?: string;
  physicalId?: string;
};

type RegisterBatchBody = {
  name: string;
  batch?: string;
  count: number;
  manufacturerAddress?: string;
};

type TransferBody = {
  toAddress: string;
};

type ConsumeBody = {
  secret: string;
};

@Controller('products')
export class ProductsController {
  constructor(private readonly productsService: ProductsService) {}

  @Get()
  list(
    @Query('owner') owner?: string,
    @Query('status') status?: string,
    @Query('q') q?: string,
    @Query('page') page?: string,
    @Query('limit') limit?: string,
  ) {
    return this.productsService.listProducts({
      owner,
      status,
      q,
      page: page ? Number(page) : undefined,
      limit: limit ? Number(limit) : undefined,
    });
  }

  @Post()
  register(@Body() body: RegisterProductBody) {
    return this.productsService.registerProduct(body);
  }

  @Post('batch')
  registerBatch(@Body() body: RegisterBatchBody) {
    if (!body?.name || body.count == null) {
      throw new BadRequestException('name and count are required');
    }
    return this.productsService.registerBatch(body);
  }

  @Post(':id/transfer')
  transfer(@Param('id') id: string, @Body() body: TransferBody) {
    return this.productsService.transferProduct(id, body.toAddress);
  }

  @Post(':id/consume')
  consume(@Param('id') id: string, @Body() body: ConsumeBody) {
    return this.productsService.consumeProduct(id, body.secret);
  }

  @Get(':id/history')
  getHistory(@Param('id') id: string) {
    return this.productsService.getOwnershipHistory(id);
  }

  @Get(':chainProductId')
  getByChainId(@Param('chainProductId') chainProductId: string) {
    return this.productsService.getByChainProductId(chainProductId);
  }
}
