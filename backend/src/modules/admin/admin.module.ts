import { Module } from '@nestjs/common';
import { RelayerModule } from '../../infrastructure/blockchain/relayer.module';
import { AdminRelayerController } from './admin-relayer.controller';

@Module({
  imports: [RelayerModule],
  controllers: [AdminRelayerController],
})
export class AdminModule {}
