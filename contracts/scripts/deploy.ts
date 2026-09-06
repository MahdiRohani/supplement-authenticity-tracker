import hre from "hardhat";
import { ethers } from "hardhat";
import * as fs from "fs";
import * as path from "path";

async function main() {
  const [deployer] = await ethers.getSigners();
  const registry = await ethers.deployContract("SupplementRegistry", [
    deployer.address,
  ]);
  await registry.waitForDeployment();

  const address = await registry.getAddress();
  const network = await ethers.provider.getNetwork();
  const networkName = (hre.network.name || `chain-${network.chainId}`).replace(
    /[^a-zA-Z0-9_-]/g,
    "_"
  );

  const artifactPath = path.join(
    __dirname,
    "../artifacts/contracts/SupplementRegistry.sol/SupplementRegistry.json"
  );
  const artifact = JSON.parse(fs.readFileSync(artifactPath, "utf8"));
  const abisDir = path.join(__dirname, "../../packages/abis");
  fs.mkdirSync(abisDir, { recursive: true });
  fs.writeFileSync(
    path.join(abisDir, "SupplementRegistry.json"),
    JSON.stringify(
      {
        contractName: "SupplementRegistry",
        address,
        chainId: Number(network.chainId),
        network: networkName,
        abi: artifact.abi,
      },
      null,
      2
    )
  );

  const deploymentsDir = path.join(__dirname, "../deployments");
  fs.mkdirSync(deploymentsDir, { recursive: true });
  const deployment = {
    SupplementRegistry: address,
    deployer: deployer.address,
    chainId: Number(network.chainId),
    network: networkName,
  };
  fs.writeFileSync(
    path.join(deploymentsDir, `${networkName}.json`),
    JSON.stringify(deployment, null, 2)
  );

  console.log(`SupplementRegistry=${address}`);
  console.log(`deployer=${deployer.address}`);
  console.log(`chainId=${network.chainId}`);
  console.log(`network=${networkName}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
