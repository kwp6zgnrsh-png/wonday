import type { FarmSalaryAccount } from "./farmSalaryAccount";

export type FarmNationality = "내국인" | "외국인";
export type FarmMember = {
  id: string;
  name: string;
  role: "근로자" | "고용주";
  nationality: FarmNationality;
  passwordHash: string;
  salt: string;
  salaryAccount?: FarmSalaryAccount;
};

export function identityNumberError(front: string, back: string, nationality: FarmNationality) {
  const label = nationality === "내국인" ? "주민등록번호" : "외국인등록번호";
  if (!/^\d{6}$/.test(front) || !/^\d{7}$/.test(back)) return `${label} 앞 6자리와 뒤 7자리를 입력해 주세요.`;
  const codes = nationality === "내국인" ? "1234" : "5678";
  if (!codes.includes(back[0])) return `${label} 구분에 맞는 번호인지 확인해 주세요.`;
  const century = "1256".includes(back[0]) ? 1900 : 2000;
  const year = century + Number(front.slice(0, 2));
  const month = Number(front.slice(2, 4));
  const day = Number(front.slice(4, 6));
  const date = new Date(year, month - 1, day);
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day || date > new Date())
    return "등록번호의 생년월일을 확인해 주세요.";
  return "";
}

export async function hashFarmPassword(password: string, salt: string) {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", encoder.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: encoder.encode(salt), iterations: 100000, hash: "SHA-256" }, key, 256);
  return Array.from(new Uint8Array(bits), (byte) => byte.toString(16).padStart(2, "0")).join("");
}
