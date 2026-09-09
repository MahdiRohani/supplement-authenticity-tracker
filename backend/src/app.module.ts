import { APP_GUARD } from '@nestjs/core';
import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { LoggerModule } from 'nestjs-pino';
import { AppConfigModule } from './config/app-config.module';
import { validateEnv } from './config/env.validation';
import { AuditModule } from './infrastructure/audit/audit.module';
import { IndexerModule } from './infrastructure/blockchain/indexer.module';
import { CacheModule } from './infrastructure/cache/cache.module';
import { IpfsModule } from './infrastructure/ipfs/ipfs.module';
import { PrismaModule } from './infrastructure/prisma/prisma.module';
import { RateLimitMiddleware } from './infrastructure/rate-limit/rate-limit.middleware';
import { RateLimitModule } from './infrastructure/rate-limit/rate-limit.module';
import { AnalyticsModule } from './modules/analytics/analytics.module';
import { FlagsModule } from './modules/flags/flags.module';
import { MetaModule } from './modules/meta/meta.module';
import { ProductsModule } from './modules/products/products.module';
import { ReportsModule } from './modules/reports/reports.module';
import { RolesModule } from './modules/roles/roles.module';
import { VerifyModule } from './modules/verify/verify.module';
import { AdminModule } from './modules/admin/admin.module';
import { HealthController } from './health.controller';
import { ApiWriteGuard } from './security/api-write.guard';
import { ChainsController } from './modules/chains/chains.controller';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, validate: validateEnv }),
    LoggerModule.forRoot({
      pinoHttp: {
        level: process.env.LOG_LEVEL ?? 'info',
      },
    }),
    AppConfigModule,
    PrismaModule,
    CacheModule,
    AuditModule,
    RateLimitModule,
    IndexerModule,
    IpfsModule,
    ProductsModule,
    RolesModule,
    VerifyModule,
    AdminModule,
    FlagsModule,
    ReportsModule,
    AnalyticsModule,
    MetaModule,
  ],
  controllers: [HealthController, ChainsController],
  providers: [
    {
      provide: APP_GUARD,
      useClass: ApiWriteGuard,
    },
  ],
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer.apply(RateLimitMiddleware).forRoutes('*');
  }
}
