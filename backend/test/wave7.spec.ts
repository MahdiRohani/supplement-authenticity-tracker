import { ConfigService } from '@nestjs/config';
import { Wallet, keccak256, toUtf8Bytes } from 'ethers';
import { Eip712Service } from '../src/infrastructure/blockchain/eip712.service';
import { FeatureFlagsService } from '../src/config/feature-flags.service';

describe('Wave7 eip712 and flags', () => {
  it('round-trips manufacturer metadata signatures', async () => {
    const wallet = Wallet.createRandom();
    const config = {
      get: (key: string) =>
        key === 'REGISTRY_ADDRESS'
          ? '0x5FbDB2315678afecb367f032d93F642f64180aa3'
          : undefined,
    } as ConfigService;
    const service = new Eip712Service(config);
    const metadataHash = keccak256(toUtf8Bytes('demo-meta'));
    const signature = await service.signManufacturerMetadata({
      privateKey: wallet.privateKey,
      metadataHash,
      manufacturer: wallet.address,
      chainId: 31337,
      nonce: 1,
    });
    const recovered = service.verifyManufacturerMetadata({
      signature,
      metadataHash,
      manufacturer: wallet.address,
      chainId: 31337,
      nonce: 1,
    });
    expect(recovered.toLowerCase()).toBe(wallet.address.toLowerCase());
  });

  it('reads feature flags from env with defaults', () => {
    const config = {
      get: (key: string) => (key === 'FF_SUBGRAPH' ? 'true' : undefined),
    } as ConfigService;
    const flags = new FeatureFlagsService(config).getFlags();
    expect(flags.reportsEnabled).toBe(true);
    expect(flags.subgraphPreferred).toBe(true);
  });
});
