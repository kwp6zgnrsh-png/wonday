import { useEffect, useEffectEvent, useLayoutEffect, useRef, useState } from "react";
import { agriworkName } from "./agriworkBrand";

type LinkProps = {
  hasRecord: boolean;
  onComplete: () => void;
  onVisitPlatform: () => void;
  onBack: () => void;
};

export function ConnectionScreen({ hasRecord, onComplete, onVisitPlatform, onBack }: LinkProps) {
  const [status, setStatus] = useState<"checking" | "missing" | "done">("checking");
  const finish = useEffectEvent(onComplete);
  useEffect(() => {
    if (status !== "checking") return;
    const timer = window.setTimeout(() => setStatus(hasRecord ? "done" : "missing"), 1400);
    return () => window.clearTimeout(timer);
  }, [status, hasRecord]);
  useEffect(() => {
    if (status !== "done") return;
    const timer = window.setTimeout(() => finish(), 900);
    return () => window.clearTimeout(timer);
  }, [status]);

  return <section className="connection-screen" aria-label={`${agriworkName} 연동`}>
    <div className="connection-brand"><b>WONDAY</b><span className="insurer-wordmark">우리손해보험</span></div>
    <div className="connection-content" role="status" aria-live="polite" aria-atomic="true">
      <div className={"connection-symbol " + status} aria-hidden="true">
        {status === "checking" && <span className="connection-ring" />}
        <span className="connection-center">
          {status === "checking" ? "W" : status === "missing" ? "?" : <i className="connection-check" />}
        </span>
      </div>
      <h2>{status === "checking" ? "연동 중이에요" : status === "done" ? "완료!" : "연동 정보가 없어요"}</h2>
      <p>{status === "checking" ? `${agriworkName}에서 내 근로자 정보를 확인하고 있어요.` :
        status === "done" ? "근로자 계정을 연결했어요." : `${agriworkName}에서 근로자 계정을 확인해 주세요.`}</p>
    </div>
    {status === "missing" && <div className="connection-actions">
        <button className="primary" onClick={onVisitPlatform}>{agriworkName}으로 이동 ↗</button>
        <button className="back" onClick={onBack}>동의 화면으로 돌아가기</button>
    </div>}
  </section>;
}

export function PlatformConsent({ consented, onChange }: { consented: boolean; onChange: (value: boolean) => void }) {
  return <>
    <h3>{agriworkName} 연동</h3>
    <p>보험 가입과 보상 심사를 위해 내 근로자 계정의 확정 근무, 근무 조건, 취소 기록을 조회해요.</p>
    <label className="check-row">
      <input type="checkbox" checked={consented} onChange={e => onChange(e.target.checked)} />
      개인정보 수집·이용 및 {agriworkName} 근무 정보 조회에 동의합니다.
    </label>
  </>;
}

export function IdentityFields({ onVerified, verified }: { onVerified: () => void; verified: boolean }) {
  const [field, setField] = useState(0);
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [resident, setResident] = useState("");
  const input = useRef<HTMLInputElement>(null);
  const label = ["이름", "전화번호", "주민등록번호"][field];
  const phoneReady = /^01[016789]\d{8}$/.test(phone) || /^01[16789]\d{7}$/.test(phone);
  const ready = [name.trim().length > 0, phoneReady, resident.length === 13][field];

  useLayoutEffect(() => {
    if (!verified) input.current?.focus({ preventScroll: true });
  }, [field, verified]);

  function advance() {
    if (!ready) return;
    if (field < 2) {
      setField(field + 1);
    } else {
      setResident("");
      onVerified();
    }
  }

  if (verified) {
    return <div className="identity-success" role="status">
      <span aria-hidden="true">✓</span>
      <h3>인증을 완료했어요</h3>
    </div>;
  }

  return (
    <div className="identity-form">
      <div className="identity-field" key={field}>
        <label htmlFor="identity-input">{label}</label>
        <input
          ref={input}
          id="identity-input"
          type={field === 2 ? "password" : field === 1 ? "tel" : "text"}
          inputMode={field === 2 ? "numeric" : field === 1 ? "tel" : "text"}
          autoComplete="off"
          enterKeyHint={field === 2 ? "done" : "next"}
          placeholder={["가상 이름 입력", "010-0000-0000", "가상 번호 13자리"][field]}
          value={[name, phone.replace(/^(\d{3})(\d{3,4})(\d{4})$/, "$1-$2-$3"), resident][field]}
          onChange={(event) => {
            const value = event.target.value;
            if (field === 0) setName(value);
            if (field === 1) {
              const digits = value.replace(/\D/g, "").slice(0, 11);
              setPhone(digits);
              if (/^01[016789]\d{8}$/.test(digits)) setField(2);
            }
            if (field === 2) setResident(value.replace(/\D/g, "").slice(0, 13));
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.nativeEvent.isComposing && event.keyCode !== 229) {
              event.preventDefault();
              advance();
            }
          }}
        />
        <p>간편 인증 체험에는 가상 정보를 입력해 주세요.</p>
      </div>
      <div className="identity-actions">
        {field > 0 && <button className="secondary" onClick={() => {
          setResident("");
          setField(field - 1);
        }}>이전 입력</button>}
        <button className="primary" disabled={!ready} onClick={advance}>
          {field === 2 ? "간편 인증 체험" : "다음"}
        </button>
      </div>
    </div>
  );
}
