export type FarmSalaryAccount = { bank: string; accountNumber: string; holder: string; verification?: "demo" };

export function readSalaryAccount(data: FormData, requireVerification = false): { account?: FarmSalaryAccount; error: string } {
  const bank = String(data.get("salaryBank") ?? "").trim();
  const rawNumber = String(data.get("salaryNumber") ?? "").trim();
  const holder = String(data.get("salaryHolder") ?? "").trim();
  if (!bank && !rawNumber && !holder) return { error: "" };
  if (!bank || !rawNumber || !holder) return { error: "급여 계좌를 등록하려면 은행명, 계좌번호, 예금주를 모두 입력해 주세요." };
  const accountNumber = rawNumber.replace(/[\s-]/g, "");
  if (!/^\d{6,20}$/.test(accountNumber)) return { error: "계좌번호는 숫자 6~20자리로 입력해 주세요." };
  const verified = data.get("salaryVerification") === JSON.stringify([bank, accountNumber, holder]);
  if (requireVerification && !verified) return { error: "계좌 체험 인증을 완료해 주세요." };
  return { account: { bank, accountNumber, holder, ...(verified ? { verification: "demo" as const } : {}) }, error: "" };
}
