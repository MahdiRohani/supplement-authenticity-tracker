import { Module } from '@nestjs/common';
import { RelayerKeyStore } from './relayer-key.store';
import { RelayerService } from './relayer.service';

@Module({
  providers: [RelayerKeyStore, RelayerService],
  exports: [RelayerService, RelayerKeyStore],
})
export class RelayerModule {}
