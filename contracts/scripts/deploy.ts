import hre from "hardhat";
import { ethers } from "hardhat";
import * as fs from "fs";
import * as path from "path";

const V1_ABI_VERSION = "1.3.0";
const V2_ABI_VERSION = "2.0.0";

interface Deployed {
  contractName: string;
  abiVersion: string;
  address: string;
  deployBlock: number;
}

async function deploy(contractName: string, admin: string, abiVersion: string): Promise<Deployed> {
  const contract = await ethers.deployContract(contractName, [admin]);
  await contract.waitForDeployment();
  const receipt = await contract.deploymentTransaction()!.wait();
  return {
    contractName,
    abiVersion,
    address: await contract.getAddress(),
    deployBlock: receipt!.blockNumber,
  };
}

function writeArtifact(
  abisDir: string,
  deployed: Deployed,
  chainId: number,
  networkName: string
) {
  const artifactPath = path.join(
    __dirname,
    `../artifacts/contracts/${deployed.contractName}.sol/${deployed.contractName}.json`
  );
  const artifact = JSON.parse(fs.readFileSync(artifactPath, "utf8"));
  fs.writeFileSync(
    path.join(abisDir, `${deployed.contractName}.json`),
    JSON.stringify(
      {
        contractName: deployed.contractName,
        abiVersion: deployed.abiVersion,
        address: deployed.address,
        chainId,
        network: networkName,
        deployBlock: deployed.deployBlock,
        abi: artifact.abi,
      },
      null,
      2
    ) + "\n"
  );
}

async function main() {
  const [deployer] = await ethers.getSigners();
  // v1 is deployed first so it keeps the deployer's first-nonce address that
  // the Android local flavor and existing tooling expect.
  const v1 = await deploy("SupplementRegistry", deployer.address, V1_ABI_VERSION);
  const v2 = await deploy("SupplementRegistryV2", deployer.address, V2_ABI_VERSION);

  const network = await ethers.provider.getNetwork();
  const chainId = Number(network.chainId);
  const networkName = (hre.network.name || `chain-${chainId}`).replace(
    /[^a-zA-Z0-9_-]/g,
    "_"
  );

  const abisDir = path.join(__dirname, "../../packages/abis");
  fs.mkdirSync(abisDir, { recursive: true });
  writeArtifact(abisDir, v1, chainId, networkName);
  writeArtifact(abisDir, v2, chainId, networkName);

  const deploymentsPath = path.join(abisDir, "deployments.json");
  let deployments: Record<string, unknown> = {};
  if (fs.existsSync(deploymentsPath)) {
    deployments = JSON.parse(fs.readFileSync(deploymentsPath, "utf8"));
  }
  // Top-level fields keep describing v1 for consumers that predate v2.
  deployments[String(chainId)] = {
    network: networkName,
    contractName: v1.contractName,
    abiVersion: v1.abiVersion,
    address: v1.address,
    deployBlock: v1.deployBlock,
    contracts: {
      [v1.contractName]: {
        abiVersion: v1.abiVersion,
        address: v1.address,
        deployBlock: v1.deployBlock,
      },
      [v2.contractName]: {
        abiVersion: v2.abiVersion,
        address: v2.address,
        deployBlock: v2.deployBlock,
      },
    },
  };
  fs.writeFileSync(deploymentsPath, JSON.stringify(deployments, null, 2) + "\n");

  const deploymentsDir = path.join(__dirname, "../deployments");
  fs.mkdirSync(deploymentsDir, { recursive: true });
  fs.writeFileSync(
    path.join(deploymentsDir, `${networkName}.json`),
    JSON.stringify(
      {
        SupplementRegistry: v1.address,
        SupplementRegistryV2: v2.address,
        deployBlocks: {
          SupplementRegistry: v1.deployBlock,
          SupplementRegistryV2: v2.deployBlock,
        },
        deployer: deployer.address,
        chainId,
        network: networkName,
        abiVersion: v1.abiVersion,
        abiVersionV2: v2.abiVersion,
      },
      null,
      2
    )
  );

  console.log(`SupplementRegistry=${v1.address}`);
  console.log(`SupplementRegistryV2=${v2.address}`);
  console.log(`deployer=${deployer.address}`);
  console.log(`chainId=${chainId}`);
  console.log(`network=${networkName}`);
  console.log(`abiVersion=${v1.abiVersion}`);
  console.log(`abiVersionV2=${v2.abiVersion}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
