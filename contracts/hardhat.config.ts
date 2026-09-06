import { HardhatUserConfig } from "hardhat/config";
import "@nomicfoundation/hardhat-toolbox";

const sepoliaRpc = process.env.SEPOLIA_RPC_URL ?? "";
const sepoliaKey = process.env.SEPOLIA_PRIVATE_KEY ?? "";

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
    hardhat: {},
    localhost: {
      url: "http://127.0.0.1:8545",
    },
    sepolia: {
      url: sepoliaRpc || "https://rpc.sepolia.org",
      accounts: sepoliaKey ? [sepoliaKey] : [],
      chainId: 11155111,
    },
  },
};

export default config;
