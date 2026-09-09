import { Controller, Post } from '@nestjs/common';
import { RelayerKeyStore } from '../../infrastructure/blockchain/relayer-key.store';

@Controller('admin/relayer-keys')
export class AdminRelayerController {
  constructor(private readonly keys: RelayerKeyStore) {}

  @Post('reload')
  reload() {
    const active = this.keys.reloadFromEnv('api');
    return {
      reloaded: true,
      activeAddresses: Object.keys(active),
    };
  }
}
