import { HardhatUserConfig } from "hardhat/config";
import "@nomicfoundation/hardhat-toolbox";
import "solidity-coverage";

const sepoliaRpc = process.env.SEPOLIA_RPC_URL ?? "";
const sepoliaKey = process.env.SEPOLIA_PRIVATE_KEY ?? "";
// HARDHAT_BLOCK_TIME_MS > 0 mines on a fixed interval instead of per transaction,
// to measure confirmation latency under L2-like (2000) or L1-like (12000) block times.
const blockTimeMs = Number(process.env.HARDHAT_BLOCK_TIME_MS ?? 0);

const config: HardhatUserConfig = {
  solidity: {
    version: "0.8.28",
    settings: {
      optimizer: {
        enabled: true,
        runs: 200,
      },
    },
  },
  paths: {
    sources: "./contracts",
    tests: "./test",
    cache: "./cache",
    artifacts: "./artifacts",
  },
  gasReporter: {
    enabled: process.env.REPORT_GAS === "true",
    currency: "USD",
  },
  networks: {
    hardhat: blockTimeMs > 0 ? { mining: { auto: false, interval: blockTimeMs } } : {},
    localhost: {
      url: process.env.LOCALHOST_RPC_URL || "http://127.0.0.1:8545",
    },
    sepolia: {
      url: sepoliaRpc || "https://rpc.sepolia.org",
      accounts: sepoliaKey ? [sepoliaKey] : [],
      chainId: 11155111,
    },
  },
};

export default config;
