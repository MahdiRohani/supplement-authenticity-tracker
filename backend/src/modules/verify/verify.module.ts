import { Module } from '@nestjs/common';
import { MemoryTtlCache } from '../../infrastructure/cache/memory-ttl.cache';
import { IpfsModule } from '../../infrastructure/ipfs/ipfs.module';
import { VerifyController } from './verify.controller';
import { VerifyService } from './verify.service';

@Module({
  imports: [IpfsModule],
  controllers: [VerifyController],
  providers: [VerifyService, MemoryTtlCache],
  exports: [VerifyService],
})
export class VerifyModule {}
