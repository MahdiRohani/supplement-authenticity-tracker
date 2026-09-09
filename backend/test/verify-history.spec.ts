import { ConflictException } from '@nestjs/common';
import { ProductStatus } from '@prisma/client';
import { MemoryTtlCache } from '../src/infrastructure/cache/memory-ttl.cache';
import { RateLimitService } from '../src/infrastructure/rate-limit/rate-limit.service';
import { VerifyService } from '../src/modules/verify/verify.service';

describe('VerifyService', () => {
  const product = {
    id: 'cuid-1',
    chainProductId: '42',
    ownerAddress: '0xabc',
    status: ProductStatus.AtPointOfSale,
    metadataCid: 'bafytest',
    metadataHash: '0xhash',
  };

  const prisma = {
    product: {
      findFirst: jest.fn(),
    },
  };
  const cache = new MemoryTtlCache();
  const ipfs = {
    resolveJson: jest.fn().mockResolvedValue({
      name: 'Vitamin D3',
      batch: 'B-001',
      expiresAt: '2027-01-01',
      image: 'ipfs://img',
    }),
    gatewayUrl: jest.fn((cid: string) => `https://ipfs.io/ipfs/${cid}`),
  };
  const config = {
    get: jest.fn((key: string) => {
      if (key === 'VERIFY_CACHE_TTL_MS') return '60000';
      return undefined;
    }),
  };

  const service = new VerifyService(
    prisma as never,
    cache,
    ipfs as never,
    config as never,
  );

  beforeEach(() => {
    cache.clear();
    jest.clearAllMocks();
  });

  it('returns authenticity from indexed product and caches it', async () => {
    prisma.product.findFirst.mockResolvedValue(product);

    const first = await service.verify('42');
    expect(first.authenticity).toBe('Authentic');
    expect(first.metadata?.name).toBe('Vitamin D3');
    expect(first.cached).toBe(false);

    const second = await service.verify('42');
    expect(second.cached).toBe(true);
    expect(prisma.product.findFirst).toHaveBeenCalledTimes(1);
  });

  it('marks consumed products with anti-refill message', async () => {
    prisma.product.findFirst.mockResolvedValue({
      ...product,
      status: ProductStatus.Consumed,
    });

    const result = await service.verify('42');
    expect(result.authenticity).toBe('Consumed');
    expect(result.message).toMatch(/already consumed/i);
  });
});

describe('ProductsService consume anti-refill', () => {
  it('rejects a second consume through relayer conflict', async () => {
    const { ProductsService } = await import(
      '../src/modules/products/products.service'
    );
    const prisma = {
      product: {
        update: jest.fn(),
      },
    };
    const relayer = {
      consume: jest
        .fn()
        .mockRejectedValueOnce(
          new ConflictException(
            'Product already consumed; refill is not allowed',
          ),
        ),
    };
    const audit = { record: jest.fn() };
    const cache = new MemoryTtlCache();
    const service = new ProductsService(
      prisma as never,
      {} as never,
      relayer as never,
      audit as never,
      cache,
    );

    await expect(
      service.consumeProduct('7', '0x' + '11'.repeat(32)),
    ).rejects.toBeInstanceOf(ConflictException);
    expect(audit.record).not.toHaveBeenCalled();
  });
});

describe('ProductsService history', () => {
  it('returns ordered ownership events with elapsedMs', async () => {
    const { ProductsService } = await import(
      '../src/modules/products/products.service'
    );
    const prisma = {
      product: {
        findFirst: jest.fn().mockResolvedValue({
          id: 'p1',
          chainProductId: '7',
          ownerAddress: '0xowner',
          status: ProductStatus.Transferred,
        }),
      },
      ownershipEvent: {
        findMany: jest.fn().mockResolvedValue([
          {
            id: 'e1',
            fromAddress: '0xa',
            toAddress: '0xb',
            txHash: '0xtx',
            blockNumber: 10n,
            createdAt: new Date('2026-01-01T00:00:00.000Z'),
          },
        ]),
      },
    };
    const service = new ProductsService(
      prisma as never,
      {} as never,
      {} as never,
      { record: jest.fn() } as never,
      new MemoryTtlCache(),
    );
    const history = await service.getOwnershipHistory('7');
    expect(history.events).toHaveLength(1);
    expect(history.events[0].blockNumber).toBe('10');
    expect(history.elapsedMs).toBeGreaterThanOrEqual(0);
  });
});

describe('ProductsService list', () => {
  it('filters by owner and paginates', async () => {
    const { ProductsService } = await import(
      '../src/modules/products/products.service'
    );
    const prisma = {
      product: {
        count: jest.fn().mockResolvedValue(1),
        findMany: jest.fn().mockResolvedValue([
          {
            id: 'p1',
            chainProductId: '7',
            ownerAddress: '0xowner',
            status: ProductStatus.Created,
            name: 'Vitamin D3',
            batchCode: 'B-1',
            metadataCid: 'bafy',
            createdAt: new Date('2026-01-01T00:00:00.000Z'),
          },
        ]),
      },
    };
    const service = new ProductsService(
      prisma as never,
      {} as never,
      {} as never,
      { record: jest.fn() } as never,
      new MemoryTtlCache(),
    );
    const page = await service.listProducts({
      owner: '0xOwner',
      page: 1,
      limit: 10,
    });
    expect(page.total).toBe(1);
    expect(page.items[0].name).toBe('Vitamin D3');
    expect(prisma.product.findMany).toHaveBeenCalled();
  });
});

describe('RateLimitService', () => {
  it('blocks after limit in window', () => {
    const limiter = new RateLimitService();
    limiter.check('verify:1', 2, 60_000);
    limiter.check('verify:1', 2, 60_000);
    expect(() => limiter.check('verify:1', 2, 60_000)).toThrow(/Too Many/);
  });
});
