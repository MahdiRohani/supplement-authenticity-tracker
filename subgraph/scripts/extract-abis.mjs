// Graph CLI wants a raw ABI array; packages/abis ships artifact wrappers
// ({ contractName, address, abi, ... }), so unwrap them into ./abis.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const source = join(root, "..", "packages", "abis");
const target = join(root, "abis");

mkdirSync(target, { recursive: true });
for (const name of ["SupplementRegistry", "SupplementRegistryV2"]) {
  const artifact = JSON.parse(readFileSync(join(source, `${name}.json`), "utf8"));
  const abi = Array.isArray(artifact) ? artifact : artifact.abi;
  if (!Array.isArray(abi)) {
    throw new Error(`${name}.json has no ABI array`);
  }
  writeFileSync(join(target, `${name}.json`), JSON.stringify(abi, null, 2) + "\n");
  console.log(`abis/${name}.json: ${abi.length} entries`);
}
