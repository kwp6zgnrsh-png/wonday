import assert from "node:assert/strict";
import { after, test } from "node:test";
import { createServer } from "vite";

const vite = await createServer({ server: { middlewareMode: true, ws: false } });
after(() => vite.close());
const { identityNumberError, hashFarmPassword } = await vite.ssrLoadModule("/src/farmRegistration.ts");

test("checks number lengths and nationality-specific first digit", () => {
  assert.notEqual(identityNumberError("90010", "1000000", "내국인"), "");
  assert.notEqual(identityNumberError("900101", "5000000", "내국인"), "");
  assert.notEqual(identityNumberError("900101", "1000000", "외국인"), "");
  assert.equal(identityNumberError("900101", "1000000", "내국인"), "");
  assert.equal(identityNumberError("900101", "5000000", "외국인"), "");
});

test("rejects nonexistent birthdays and accepts leap days", () => {
  assert.notEqual(identityNumberError("900230", "1000000", "내국인"), "");
  assert.notEqual(identityNumberError("901301", "5000000", "외국인"), "");
  assert.equal(identityNumberError("000229", "3000000", "내국인"), "");
});

test("stored password hash verifies the password and is salted", async () => {
  const hash = await hashFarmPassword("sample-password", "sample-salt");
  assert.equal(hash.length, 64);
  assert.equal(hash, await hashFarmPassword("sample-password", "sample-salt"));
  assert.notEqual(hash, await hashFarmPassword("other-password", "sample-salt"));
  assert.notEqual(hash, await hashFarmPassword("sample-password", "other-salt"));
});