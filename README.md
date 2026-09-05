# Supplement Authenticity Tracker

Blockchain-based supplement authenticity tracking: smart contracts, IPFS metadata, NestJS indexer/API, and a multi-role Android app.

## Repository layout

| Path | Purpose |
|------|---------|
| `android/` | Multi-module Android app (Compose) |
| `contracts/` | Hardhat + Solidity (added in later waves) |
| `backend/` | NestJS indexer/API (added in later waves) |
| `packages/abis/` | Shared contract ABIs (added in later waves) |

## Prerequisites

- Git
- JDK 17+
- Android Studio (Ladybug or newer recommended) with Android SDK
- Node.js 20+ (for contracts and backend, when those packages exist)

## Clone

```bash
git clone https://github.com/MahdiRohani/supplement-authenticity-tracker.git
cd supplement-authenticity-tracker
```

## Run Android app

1. Open the `android/` directory in Android Studio (not the monorepo root).
2. Let Gradle sync finish. If prompted, set the Android SDK path (creates `android/local.properties` locally; it is gitignored).
3. Select a device or emulator, then run the `:app` configuration.

From the command line:

```bash
cd android
./gradlew :app:assembleDebug
```

APK output: `android/app/build/outputs/apk/debug/`.

## Contracts and backend

Not scaffolded yet. Follow upcoming waves for Hardhat local node, NestJS API, and end-to-end flows.

## License

All rights reserved unless a license file is added later.
