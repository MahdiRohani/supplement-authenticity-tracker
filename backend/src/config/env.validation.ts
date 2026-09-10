import { z } from 'zod';

const optionalUrl = z
  .string()
  .optional()
  .transform((value) => value?.trim() || undefined)
  .pipe(z.string().url().optional());

const boolish = z.string().optional();

const envSchema = z.object({
  DATABASE_URL: z.string().min(1),
  PORT: z.coerce.number().int().positive().default(3000),
  RPC_URL: optionalUrl,
  REGISTRY_ADDRESS: z.string().optional().default(''),
  REGISTRY_ABI_PATH: z.string().optional(),
  DEPLOYMENTS_PATH: z.string().optional(),
  CHAIN_ID: z.coerce.number().int().positive().optional(),
  IPFS_API_URL: z.string().optional(),
  IPFS_GATEWAY_URL: z.string().optional(),
  VERIFY_CACHE_TTL_MS: z.coerce.number().int().positive().default(15_000),
  VERIFY_RATE_LIMIT: z.coerce.number().int().positive().default(60),
  CONSUME_RATE_LIMIT: z.coerce.number().int().positive().default(20),
  RATE_LIMIT_WINDOW_MS: z.coerce.number().int().positive().default(60_000),
  RELAYER_KEYS_JSON: z.string().default('{}'),
  RELAYER_KEYS_PREVIOUS_JSON: z.string().optional(),
  API_WRITE_KEY: z.string().optional(),
  ALLOW_IPFS_STUB: z.string().optional(),
  LOG_LEVEL: z.string().optional(),
  NODE_ENV: z
    .enum(['development', 'test', 'production'])
    .default('development'),
  FF_REPORTS: boolish,
  FF_SCAN: boolish,
  FF_LABELS_PDF: boolish,
  FF_ANALYTICS: boolish,
  FF_EIP712_METADATA: boolish,
  FF_META_TX_CONSUME: boolish,
  FF_SUBGRAPH: boolish,
});

export type AppEnv = z.infer<typeof envSchema>;

export function validateEnv(config: Record<string, unknown>): AppEnv {
  const parsed = envSchema.safeParse(config);
  if (!parsed.success) {
    const details = parsed.error.issues
      .map((issue) => `${issue.path.join('.')}: ${issue.message}`)
      .join('; ');
    throw new Error(`Invalid environment configuration: ${details}`);
  }
  const data = parsed.data;
  if (data.NODE_ENV === 'production' && !data.API_WRITE_KEY?.trim()) {
    throw new Error(
      'Invalid environment configuration: API_WRITE_KEY is required when NODE_ENV=production',
    );
  }
  if (
    data.NODE_ENV === 'production' &&
    (data.ALLOW_IPFS_STUB === 'true' || data.ALLOW_IPFS_STUB === '1')
  ) {
    throw new Error(
      'Invalid environment configuration: ALLOW_IPFS_STUB cannot be enabled in production',
    );
  }
  return data;
}
