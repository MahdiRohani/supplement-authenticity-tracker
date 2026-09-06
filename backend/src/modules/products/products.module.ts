import { Module } from '@nestjs/common';
import { IpfsModule } from '../../infrastructure/ipfs/ipfs.module';
import { ProductsController } from './products.controller';
import { ProductsService } from './products.service';

@Module({
  imports: [IpfsModule],
  controllers: [ProductsController],
  providers: [ProductsService],
})
export class ProductsModule {}
