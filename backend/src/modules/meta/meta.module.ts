import { Module } from '@nestjs/common';
import { Eip712Service } from '../../infrastructure/blockchain/eip712.service';
import { ProductsModule } from '../products/products.module';
import { MetaController } from './meta.controller';

@Module({
  imports: [ProductsModule],
  controllers: [MetaController],
  providers: [Eip712Service],
})
export class MetaModule {}
