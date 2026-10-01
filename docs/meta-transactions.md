# Gasless consumer path (meta-tx / AA)

Consumers should not need ETH, or a wallet, to mark a unit consumed. In both
protocol versions the consumer signs an EIP-712 message off-chain, and an
operator-funded **relayer** submits the transaction.

## v2: unit-key authorization (`SupplementRegistryV2`)

The signer is the **unit**, not a user wallet. Each unit has its own secp256k1
key `sk_i`. The key is printed under the scratch-off layer as the secret QR
`satk2:{chainId}:{batchId}:{index}:{sk_i}`, and its address is a Merkle leaf
of the batch (see [`protocol-v2.md`](protocol-v2.md)).

Typed data:

```text
domain      = { name: "SupplementRegistry", version: "2", chainId, verifyingContract: REGISTRY_V2_ADDRESS }
primaryType = ConsumeAuthorization(uint256 batchId, uint32 index, address consumer, uint256 deadline)
```

`GET /v2/chains` publishes the domain and types, so clients do not hard-code
them.

Flow:

1. The buyer scratches the label and scans the secret QR. The app parses it
   (`SecretLabel.parse`) and checks that the chain matches the configured
   network and the EIP-712 domain from `/v2/chains`.
2. `consumer` is the app's per-install identity address (`ConsumerKeyStore`).
   It is a label for the buyer's history, not a funded account, and it is part
   of the signed data. A relayed or copied transaction therefore cannot
   attribute the unit to someone else.
3. The app signs `ConsumeAuthorization` with `sk_i` (`Eip712ConsumeSigner`),
   with a short `deadline` (10 minutes by default).
4. The app sends a `POST /v2/consume` request:

   ```json
   { "chainId": 31337, "batchId": "1", "index": 2, "consumer": "0x…", "deadline": 1790000000, "signature": "0x…" }
   ```

   The unit key and the proof are not sent. The backend loads the proof from
   its stored manifest.
5. Before spending gas, the backend repeats the contract checks:
   - chain id;
   - deadline;
   - signature recovery;
   - whether the unit is already consumed (409);
   - whether the recovered key is the committed leaf for `index` (400);
   - whether the unit is in a segment at a pharmacy (409).

   It then calls `consume(request, proof, signature)` from a relayer key.
6. The contract checks everything again on its own. The relayer cannot
   consume a unit without a valid unit signature, and cannot change the
   consumer.

Errors follow [`protocol-v2.md`](protocol-v2.md):

| Error | Status |
| --- | --- |
| Bad signature or wrong key | 400 |
| Expired deadline | 400 |
| Already consumed (refill) | 409 |
| Not at the point of sale | 409 |
| Recalled | 409 |

Gas for the relayer is 75–85k on a cold bitmap word and 58–68k on a warm
word, depending on the batch size ([`evaluation.md`](evaluation.md)).

## v1: secret reveal (`SupplementRegistry`, v1.1)

1. Consumer signs an EIP-712 `ConsumeAuthorization` offline (`productId`, `secret`, `consumer`, `deadline`).
2. Client posts to `POST /v1/meta/consume` with the signature.
3. Backend verifies the typed data, then submits `consume` through the existing **relayer** (gas paid by operator keys).

The v1 secret is revealed in calldata when it is consumed. v2 never puts the
unit key on-chain: only a signature made with it.

## Account abstraction

Both versions use the **relayer meta-transaction** pattern. Full ERC-4337
account abstraction is out of scope for the thesis demo. A paymaster could
later sit in front of the same `ConsumeAuthorization`, because the contract
accepts consume from any sender.

## Manufacturer metadata (EIP-712, v1)

- `POST /v1/meta/eip712/metadata/sign` — demo helper to produce a manufacturer signature over `metadataHash`
- `POST /v1/meta/eip712/metadata/verify` — recover/verify manufacturer address

Domain: `SupplementRegistry` / version `1` / active `CHAIN_ID` / `REGISTRY_ADDRESS`.

Feature flags: `FF_META_TX_CONSUME`, `FF_EIP712_METADATA` (v1 only).

## Relayer keys

The relayer pool is `RELAYER_KEYS_JSON`. Rotate keys with
`RELAYER_KEYS_PREVIOUS_JSON` and `POST /v1/admin/relayer-keys/reload`. A
production deployment should cap spending per relayer and monitor balances.
The public consume endpoint is rate-limited, and the API checks everything
before relaying, so an invalid request costs no gas.
