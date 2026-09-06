import { ethers } from "hardhat";

async function main() {
  const [admin, manufacturer, distributor, pharmacy] = await ethers.getSigners();
  const registry = await ethers.deployContract("SupplementRegistry", [
    admin.address,
  ]);
  await registry.waitForDeployment();

  const manufacturerRole = ethers.id("MANUFACTURER_ROLE");
  const distributorRole = ethers.id("DISTRIBUTOR_ROLE");
  const pharmacyRole = ethers.id("PHARMACY_ROLE");

  await registry.connect(admin).grantRole(manufacturerRole, manufacturer.address);
  await registry.connect(admin).grantRole(distributorRole, distributor.address);
  await registry.connect(admin).grantRole(pharmacyRole, pharmacy.address);

  const secret = ethers.id("e2e-secret");
  const secretHash = ethers.keccak256(
    ethers.solidityPacked(["bytes32"], [secret])
  );
  const metadataCid = "bafybeigse2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2";
  const metadataHash = ethers.id("e2e-metadata");

  const productId = await registry
    .connect(manufacturer)
    .registerUnit.staticCall(secretHash, metadataCid, metadataHash);
  await registry
    .connect(manufacturer)
    .registerUnit(secretHash, metadataCid, metadataHash);

  await registry
    .connect(manufacturer)
    .transferOwnership(productId, distributor.address);
  await registry
    .connect(distributor)
    .transferOwnership(productId, pharmacy.address);

  const product = await registry.getProduct(productId);
  console.log(
    JSON.stringify(
      {
        productId: productId.toString(),
        owner: product.owner,
        status: Number(product.status),
        registry: await registry.getAddress(),
        path: "Manufacturer -> Distributor -> Pharmacy",
      },
      null,
      2
    )
  );

  const backendUrl = process.env.BACKEND_URL;
  if (backendUrl) {
    const response = await fetch(
      `${backendUrl.replace(/\/$/, "")}/v1/products/${productId}/history`
    );
    const history = await response.json();
    console.log("history=", JSON.stringify(history, null, 2));
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
