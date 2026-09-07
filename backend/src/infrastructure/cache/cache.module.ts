import { Global, Module } from '@nestjs/common';
import { MemoryTtlCache } from './memory-ttl.cache';

@Global()
@Module({
  providers: [MemoryTtlCache],
  exports: [MemoryTtlCache],
})
export class CacheModule {}
