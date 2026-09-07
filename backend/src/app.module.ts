import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { AuditModule } from './infrastructure/audit/audit.module';
import { IndexerModule } from './infrastructure/blockchain/indexer.module';
import { CacheModule } from './infrastructure/cache/cache.module';
import { IpfsModule } from './infrastructure/ipfs/ipfs.module';
import { PrismaModule } from './infrastructure/prisma/prisma.module';
import { RateLimitMiddleware } from './infrastructure/rate-limit/rate-limit.middleware';
import { RateLimitModule } from './infrastructure/rate-limit/rate-limit.module';
import { ProductsModule } from './modules/products/products.module';
import { RolesModule } from './modules/roles/roles.module';
import { VerifyModule } from './modules/verify/verify.module';
import { HealthController } from './health.controller';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    PrismaModule,
    CacheModule,
    AuditModule,
    RateLimitModule,
    IndexerModule,
    IpfsModule,
    ProductsModule,
    RolesModule,
    VerifyModule,
  ],
  controllers: [HealthController],
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer.apply(RateLimitMiddleware).forRoutes('*');
  }
}
