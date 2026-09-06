import { Module } from '@nestjs/common';
import { RelayerModule } from '../../infrastructure/blockchain/relayer.module';
import { IpfsModule } from '../../infrastructure/ipfs/ipfs.module';
import { ProductsController } from './products.controller';
import { ProductsService } from './products.service';

@Module({
  imports: [IpfsModule, RelayerModule],
  controllers: [ProductsController],
  providers: [ProductsService],
})
export class ProductsModule {}
