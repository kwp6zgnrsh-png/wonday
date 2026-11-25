import { useState } from "react";
import { hashFarmPassword, identityNumberError } from "./farmRegistration";
import type { FarmMember, FarmNationality } from "./farmRegistration";

export default function FarmSignup({ initialRole, members, onRegister, onCancel }: {
  initialRole: string;
  members: readonly FarmMember[];
  onRegister: (member: FarmMember) => void;
  onCancel: () => void;
}) {
  const [nationality, setNationality] = useState<FarmNationality>("내국인");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const identityLabel = nationality === "내국인" ? "주민등록번호" : "외국인등록번호";
  return <section className="farm-signup" aria-labelledby="farm-signup-title">
    <h2 id="farm-signup-title">회원가입</h2>
    <p>회원 구분과 가입 정보를 입력해 주세요.</p>
    <form onSubmit={async (event) => {
      event.preventDefault();
      if (submitting) return;
      const form = event.currentTarget;
      const data = new FormData(form);
      const id = String(data.get("memberId") ?? "").trim();
      const password = String(data.get("newPassword") ?? "");
      const name = String(data.get("memberName") ?? "").trim();
      const identityError = identityNumberError(String(data.get("identityFront") ?? ""), String(data.get("identityBack") ?? ""), nationality);
      if (!name) return setError("이름을 입력해 주세요.");
      if (members.some((member) => member.id.toLowerCase() === id.toLowerCase()) || ["worker.demo", "farmer.demo"].includes(id.toLowerCase())) return setError("이미 사용 중인 아이디예요.");
      if (password !== data.get("confirmPassword")) return setError("비밀번호가 일치하지 않아요.");
      if (identityError) return setError(identityError);
      setError("");
      setSubmitting(true);
      try {
        const salt = crypto.randomUUID();
        const passwordHash = await hashFarmPassword(password, salt);
        onRegister({ id, name, role: data.get("memberRole") === "고용주" ? "고용주" : "근로자", nationality, salt, passwordHash });
        form.reset();
      } catch {
        setError("가입 정보를 저장하지 못했어요. 다시 시도해 주세요.");
      } finally {
        setSubmitting(false);
      }
    }}>
      <fieldset className="farm-signup-choice">
        <legend>이용 유형</legend>
        <div>{["근로자", "고용주"].map((value) => <label key={value}>
          <input type="radio" name="memberRole" value={value} defaultChecked={value === initialRole} required />{value}
        </label>)}</div>
      </fieldset>
      <fieldset className="farm-signup-choice">
        <legend>회원 구분</legend>
        <div>{(["내국인", "외국인"] as const).map((value) => <label key={value}>
          <input type="radio" name="nationality" value={value} checked={nationality === value}
            onChange={() => { setNationality(value); setError(""); }} />{value}
        </label>)}</div>
      </fieldset>
      <label>이름<input name="memberName" autoComplete="name" maxLength={60} required /></label>
      <fieldset className="farm-signup-identity" key={nationality}>
        <legend>{identityLabel}</legend>
        <div>
          <input name="identityFront" aria-label={`${identityLabel} 앞 6자리`} type="text" inputMode="numeric" autoComplete="off" pattern="[0-9]{6}" maxLength={6} placeholder="앞 6자리" required />
          <span aria-hidden="true">−</span>
          <input name="identityBack" aria-label={`${identityLabel} 뒤 7자리`} type="password" inputMode="numeric" autoComplete="off" pattern="[0-9]{7}" maxLength={7} placeholder="뒤 7자리" required />
        </div>
      </fieldset>
      <label>아이디<input name="memberId" autoComplete="username" minLength={4} maxLength={20} pattern="[A-Za-z0-9._-]{4,20}" aria-describedby="farm-signup-id-help" required /></label>
      <p id="farm-signup-id-help" className="farm-signup-help">영문·숫자·점·밑줄·하이픈 4~20자</p>
      <label>비밀번호<input name="newPassword" type="password" autoComplete="new-password" minLength={8} maxLength={64} placeholder="8자 이상" required /></label>
      <label>비밀번호 확인<input name="confirmPassword" type="password" autoComplete="new-password" minLength={8} maxLength={64} required /></label>
      <p className="farm-signup-help">체험용 가입이에요. 실제 등록번호를 입력하지 마세요. 입력한 등록번호는 저장하지 않아요.</p>
      {error && <p className="farm-signup-error" role="alert">{error}</p>}
      <button type="submit" className="primary" disabled={submitting}>{submitting ? "가입 처리 중…" : "회원가입 완료"}</button>
      <button type="button" className="text-button" disabled={submitting} onClick={onCancel}>로그인으로 돌아가기</button>
    </form>
  </section>;
}
