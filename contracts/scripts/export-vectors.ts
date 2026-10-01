import * as fs from "fs";
import * as path from "path";
import { buildVectors } from "./lib/vectors";

export const VECTORS_PATH = path.join(
  __dirname,
  "../../packages/abis/test-vectors/supplement-registry-v2.json"
);

async function main() {
  const vectors = await buildVectors();
  fs.mkdirSync(path.dirname(VECTORS_PATH), { recursive: true });
  fs.writeFileSync(VECTORS_PATH, JSON.stringify(vectors, null, 2) + "\n");
  console.log(`vectors=${path.relative(process.cwd(), VECTORS_PATH)}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
