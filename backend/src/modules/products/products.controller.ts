import { Body, Controller, Get, Param, Post } from '@nestjs/common';
import { ProductsService } from './products.service';

type RegisterProductBody = {
  name: string;
  batch?: string;
  manufacturerAddress?: string;
};

@Controller('products')
export class ProductsController {
  constructor(private readonly productsService: ProductsService) {}

  @Post()
  register(@Body() body: RegisterProductBody) {
    return this.productsService.registerProduct(body);
  }

  @Get(':chainProductId')
  getByChainId(@Param('chainProductId') chainProductId: string) {
    return this.productsService.getByChainProductId(chainProductId);
  }
}
