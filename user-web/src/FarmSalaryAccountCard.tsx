import { useId, useRef, useState } from "react";
import { readSalaryAccount } from "./farmSalaryAccount";
import type { FarmSalaryAccount } from "./farmSalaryAccount";

const salaryBanks = [
  "우리은행", "KB국민은행", "신한은행", "하나은행", "NH농협은행", "지역농축협",
  "IBK기업은행", "KDB산업은행", "Sh수협은행", "SC제일은행", "한국씨티은행",
  "부산은행", "경남은행", "광주은행", "전북은행", "제주은행",
  "케이뱅크", "카카오뱅크", "토스뱅크", "우체국", "새마을금고", "신협", "저축은행",
] as const;

export function SalaryAccountFields({ value, employer = false }: { value?: FarmSalaryAccount; employer?: boolean }) {
  const noteId = useId();
  const bankPicker = useRef<HTMLDetailsElement>(null);
  const [bank, setBank] = useState(value?.bank ?? "");
  const [number, setNumber] = useState(value?.accountNumber ?? "");
  const [holder, setHolder] = useState(value?.holder ?? "");
  const identity = JSON.stringify([bank.trim(), number.replace(/[\s-]/g, ""), holder.trim()]);
  const [verifiedKey, setVerifiedKey] = useState(value?.verification === "demo" ? identity : "");
  const [challengeKey, setChallengeKey] = useState("");
  const [code, setCode] = useState("");
  const [verificationError, setVerificationError] = useState("");
  const verified = verifiedKey === identity;
  return <fieldset className="farm-salary-fields" aria-describedby={noteId}>
    <legend>급여 {employer ? "지급" : "수령"} 계좌 <span>(선택)</span></legend>
    <div className="farm-bank-field">
      <span id={`${noteId}-bank`}>은행명</span>
      <input type="hidden" name="salaryBank" value={bank} />
      <details className="farm-multi-select farm-bank-picker" ref={bankPicker}>
        <summary aria-labelledby={`${noteId}-bank ${noteId}-selected`}>
          <span id={`${noteId}-selected`}>{bank || "은행 선택"}</span>
          <svg className="farm-multi-chevron" viewBox="0 0 12 12" fill="currentColor" aria-hidden="true"><path d="M1 1.67h10L6 10.33Z" /></svg>
        </summary>
        <div className="farm-bank-options" role="group" aria-label="은행 선택">
          {["", ...salaryBanks, ...(bank && !salaryBanks.some((item) => item === bank) ? [bank] : [])].map((item) => (
            <button key={item} type="button" aria-pressed={bank === item} onClick={() => {
              setBank(item); setVerifiedKey(""); setChallengeKey(""); setVerificationError("");
              if (bankPicker.current) bankPicker.current.open = false;
            }}>{item || "선택 안 함"}{bank === item && <span aria-hidden="true">✓</span>}</button>
          ))}
        </div>
      </details>
    </div>
    <label>계좌번호<input name="salaryNumber" value={number} onChange={(event) => { setNumber(event.currentTarget.value); setVerifiedKey(""); setChallengeKey(""); setVerificationError(""); }} inputMode="numeric" maxLength={30} placeholder="숫자만 입력" autoComplete="off" /></label>
    <label>예금주<input name="salaryHolder" value={holder} onChange={(event) => { setHolder(event.currentTarget.value); setVerifiedKey(""); setChallengeKey(""); setVerificationError(""); }} maxLength={60} placeholder="예금주 이름" autoComplete="off" /></label>
    <input type="hidden" name="salaryVerification" value={verified ? identity : ""} />
    <div className="farm-account-verification">
      <strong>{verified ? "체험 인증 완료" : "계좌 인증"}</strong>
      <p className="fine">체험용 인증이에요. 실제 은행 조회·입금·계좌 소유 확인은 진행되지 않아요.</p>
      {!verified && (challengeKey === identity ? <>
        <p className="fine">체험 인증번호 <b>1234</b>를 입력해 주세요.</p>
        <label>체험 인증번호<input inputMode="numeric" maxLength={4} value={code} onChange={(event) => setCode(event.currentTarget.value)} autoComplete="off" /></label>
        <button type="button" className="secondary" onClick={() => {
          if (code !== "1234") return setVerificationError("체험 인증번호 1234를 입력해 주세요.");
          setVerifiedKey(identity); setVerificationError(""); setCode("");
        }}>인증 확인</button>
      </> : <button type="button" className="secondary" onClick={() => {
        const data = new FormData(); data.set("salaryBank", bank); data.set("salaryNumber", number); data.set("salaryHolder", holder);
        const result = readSalaryAccount(data);
        if (result.error || !result.account) return setVerificationError(result.error || "은행명, 계좌번호, 예금주를 먼저 입력해 주세요.");
        setChallengeKey(identity); setCode(""); setVerificationError("");
      }}>계좌 체험 인증</button>)}
      {verificationError && <p className="farm-salary-error" role="alert">{verificationError}</p>}
      {verified && <p className="fine" role="status">계좌 정보 변경 시 다시 인증해야 해요. 실제 지급용 인증은 은행 연동 후 제공돼요.</p>}
    </div>
    <p id={noteId} className="farm-salary-note">연동하면 근무 완료 처리 후, 즉시 급여 {employer ? "지급" : "수령"} 가능</p>
  </fieldset>;
}

export default function FarmSalaryAccountCard({ value, onSave, employer = false }: {
  employer?: boolean;
  value?: FarmSalaryAccount;
  onSave: (value: FarmSalaryAccount | undefined) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState("");
  const titleId = useId();
  return <section className="farm-profile-section farm-salary-section" aria-labelledby={titleId}>
    <h3 id={titleId}>급여 {employer ? "지급" : "수령"} 계좌</h3>
    <div className="farm-salary-card">
      {editing ? <form aria-label={`급여 ${employer ? "지급" : "수령"} 계좌 수정`} onSubmit={(event) => {
        event.preventDefault();
        const result = readSalaryAccount(new FormData(event.currentTarget), true);
        if (result.error) return setError(result.error);
        try {
          onSave(result.account);
          setEditing(false);
          setError("");
        } catch { setError("계좌 정보를 저장하지 못했어요. 다시 시도해 주세요."); }
      }}>
        <SalaryAccountFields value={value} employer={employer} />
        {error && <p className="farm-salary-error" role="alert">{error}</p>}
        <div className="farm-salary-actions">
          <button type="button" className="secondary" onClick={() => { setEditing(false); setError(""); }}>취소</button>
          <button type="submit" className="primary">계좌 저장</button>
        </div>
      </form> : <>
        {value ? <div className="farm-salary-summary">
          <strong>{value.bank}</strong>
          <span>•••• {value.accountNumber.slice(-4)}</span>
          <span>{value.holder}</span>
          <span className="farm-account-verification-label">{value.verification === "demo" ? "체험 인증 완료 · 은행 인증 전" : "계좌 인증 필요"}</span>
        </div> : <p>등록된 계좌가 없어요.</p>}
        <p className="farm-salary-note">연동하면 근무 완료 처리 후, 즉시 급여 {employer ? "지급" : "수령"} 가능</p>
        <div className="farm-salary-actions">
          {value && <button type="button" className="secondary" onClick={() => {
            try { onSave(undefined); } catch { setError("계좌 정보를 삭제하지 못했어요. 다시 시도해 주세요."); }
          }}>계좌 삭제</button>}
          <button type="button" className="primary" onClick={() => { setError(""); setEditing(true); }}>{value ? "계좌 수정" : "계좌 등록"}</button>
        </div>
        {error && <p className="farm-salary-error" role="alert">{error}</p>}
      </>}
    </div>
  </section>;
}
