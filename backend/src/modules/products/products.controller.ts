import { Body, Controller, Get, Param, Post } from '@nestjs/common';
import { ProductsService } from './products.service';

type RegisterProductBody = {
  name: string;
  batch?: string;
  manufacturerAddress?: string;
  physicalId?: string;
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

  @Post()
  register(@Body() body: RegisterProductBody) {
    return this.productsService.registerProduct(body);
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
