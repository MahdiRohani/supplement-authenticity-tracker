import { Body, Controller, Get, Param, Post } from '@nestjs/common';
import { ProductsService } from './products.service';

type RegisterProductBody = {
  name: string;
  batch?: string;
  manufacturerAddress?: string;
};

type TransferBody = {
  toAddress: string;
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

  @Get(':id/history')
  getHistory(@Param('id') id: string) {
    return this.productsService.getOwnershipHistory(id);
  }

  @Get(':chainProductId')
  getByChainId(@Param('chainProductId') chainProductId: string) {
    return this.productsService.getByChainProductId(chainProductId);
  }
}
