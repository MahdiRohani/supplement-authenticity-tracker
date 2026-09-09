# Gasless consumer path (meta-tx / AA)

Consumers should not need ETH to mark a unit consumed.

## Supported path (v1.1)

1. Consumer signs an EIP-712 `ConsumeAuthorization` offline (`productId`, `secret`, `consumer`, `deadline`).
2. Client posts to `POST /v1/meta/consume` with the signature.
3. Backend verifies the typed data, then submits `consume` through the existing **relayer** (gas paid by operator keys).

This is a **relayer meta-transaction** pattern. Full ERC-4337 account abstraction is out of scope for the thesis demo but can sit in front of the same consume authorization later.

## Manufacturer metadata (EIP-712)

- `POST /v1/meta/eip712/metadata/sign` — demo helper to produce a manufacturer signature over `metadataHash`
- `POST /v1/meta/eip712/metadata/verify` — recover/verify manufacturer address

Domain: `SupplementRegistry` / version `1` / active `CHAIN_ID` / `REGISTRY_ADDRESS`.

Feature flags: `FF_META_TX_CONSUME`, `FF_EIP712_METADATA`.
