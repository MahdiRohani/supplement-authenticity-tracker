import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { IndexerModule } from './infrastructure/blockchain/indexer.module';
import { IpfsModule } from './infrastructure/ipfs/ipfs.module';
import { PrismaModule } from './infrastructure/prisma/prisma.module';
import { ProductsModule } from './modules/products/products.module';
import { RolesModule } from './modules/roles/roles.module';
import { HealthController } from './health.controller';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    PrismaModule,
    IndexerModule,
    IpfsModule,
    ProductsModule,
    RolesModule,
  ],
  controllers: [HealthController],
})
export class AppModule {}
