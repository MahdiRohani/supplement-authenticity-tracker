import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { IndexerModule } from './infrastructure/blockchain/indexer.module';
import { PrismaModule } from './infrastructure/prisma/prisma.module';
import { HealthController } from './health.controller';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    PrismaModule,
    IndexerModule,
  ],
  controllers: [HealthController],
})
export class AppModule {}
