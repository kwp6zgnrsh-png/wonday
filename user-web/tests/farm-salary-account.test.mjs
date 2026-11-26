import assert from "node:assert/strict";
import { after, test } from "node:test";
import { createServer } from "vite";

const vite = await createServer({ server: { middlewareMode: true, ws: false } });
after(() => vite.close());
const { readSalaryAccount } = await vite.ssrLoadModule("/src/farmSalaryAccount.ts");
const fields = (bank = "", number = "", holder = "") => {
  const data = new FormData();
  data.set("salaryBank", bank);
  data.set("salaryNumber", number);
  data.set("salaryHolder", holder);
  return data;
};

test("salary account can be omitted entirely", () => {
  assert.deepEqual(readSalaryAccount(fields()), { error: "" });
  assert.deepEqual(readSalaryAccount(fields(" ", " ", " ")), { error: "" });
});
test("partially entered accounts require the remaining fields", () => {
  assert.ok(readSalaryAccount(fields("테스트은행")).error);
  assert.ok(readSalaryAccount(fields("", "000000001234", "테스트")).error);
  assert.ok(readSalaryAccount(fields("테스트은행", "000000001234")).error);
});
test("account numbers preserve leading zeros and normalize separators", () => {
  assert.deepEqual(readSalaryAccount(fields(" 테스트은행 ", "000-000 001234", " 테스트 ")), {
    account: { bank: "테스트은행", accountNumber: "000000001234", holder: "테스트" }, error: "",
  });
  assert.ok(readSalaryAccount(fields("테스트은행", "invalid-number", "테스트")).error);
});
test("optional accounts remain optional with verification required", () => {
  assert.deepEqual(readSalaryAccount(fields(), true), { error: "" });
  assert.ok(readSalaryAccount(fields("테스트은행", "000000001234", "테스트"), true).error);
});
test("demo verification is tied to the entered bank, number and holder", () => {
  const data = fields("테스트은행", "000-000-001234", "테스트");
  data.set("salaryVerification", JSON.stringify(["테스트은행", "000000001234", "테스트"]));
  assert.equal(readSalaryAccount(data, true).account.verification, "demo");
  for (const [field, value] of [["salaryBank", "다른은행"], ["salaryNumber", "999999999999"], ["salaryHolder", "다른이름"]]) {
    const changed = new FormData();
    for (const [key, item] of data) changed.set(key, item);
    changed.set(field, value);
    assert.ok(readSalaryAccount(changed, true).error);
  }
});