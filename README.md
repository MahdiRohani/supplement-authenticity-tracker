# Supplement Authenticity Tracker

Blockchain-based supplement authenticity tracking: smart contracts, IPFS metadata, NestJS indexer/API, and a multi-role Android app.

## Repository layout

| Path | Purpose |
|------|---------|
| `android/` | Multi-module Android app (Compose), package `ir.aut.supplementtracker` |
| `contracts/` | Hardhat + Solidity (`SupplementRegistry`) |
| `backend/` | NestJS indexer/API + Prisma + IPFS adapter |
| `packages/abis/` | Shared `SupplementRegistry` ABI |

## Prerequisites

- Git
- JDK 17+
- Android Studio (Ladybug or newer recommended) with Android SDK
- Node.js 20+ (for contracts and backend)

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

- Contracts: `cd contracts && npm install && npm test && npm run deploy:local`
- Backend: see `backend/README.md` (`GET /v1/health`, `POST /v1/products`, ProductRegistered indexer)

## Branch and commits

- Default branch: `master`. Feature work lands here as sequential phase commits (or short-lived topic branches merged into `master`).
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):

```text
type(scope): short summary

optional body
```

| Type | Use |
|------|-----|
| `feat` | New user-facing capability |
| `fix` | Bug fix |
| `refactor` | Internal change without behavior change |
| `chore` | Tooling, hygiene, scaffolding |
| `test` | Tests only |
| `docs` | Documentation only |
| `ci` | CI configuration |

Scopes match packages: `repo`, `android`, `contracts`, `backend`, `abis`.

Examples: `feat(contracts): register product units`, `chore(repo): add root gitignore`.

See `.gitmessage` for a local template (optional: `git config commit.template .gitmessage`).

## License

All rights reserved unless a license file is added later.
