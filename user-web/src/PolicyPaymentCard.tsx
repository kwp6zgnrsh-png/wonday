import { initialInstallment } from "./insurancePolicies";
import type { Policy } from "./insurancePolicies";

export function PolicyPaymentCard({ policy, sample = false, billing = false, coverageStatus, onPay }: {
  policy: Policy;
  sample?: boolean;
  billing?: boolean;
  coverageStatus?: string;
  onPay?: () => void;
}) {
  const { monthlyPremium, paidInstallments, totalInstallments } =
    policy.installments ?? initialInstallment;
  const percent = Math.round((paidInstallments / totalInstallments) * 100);
  const paidInFull = paidInstallments === totalInstallments;

  return (
    <article className="card contract-card">
      <div className="contract-heading">
        <h3>{policy.name}</h3>
        {(sample || coverageStatus) && <span className="badge">{sample ? "예시 계약" : coverageStatus}</span>}
      </div>
      <dl className="contract-metrics">
        <div><dt>월 납입액</dt><dd>{monthlyPremium.toLocaleString("ko-KR")}원</dd></div>
        <div>
          <dt>납입 횟수</dt>
          <dd className="contract-installments">
            <span>{paidInstallments}회 납입</span>{" "}
            <span>/ 총 {totalInstallments}회</span>
          </dd>
        </div>
      </dl>
      <p className="contract-progress-label" aria-hidden="true">{percent}% 납입</p>
      <progress aria-label={policy.name + " 납입률"} value={paidInstallments} max={totalInstallments} />
      {billing ? (
        <>
          {policy.paymentMethod && <p className="premium-method">최근 납입 방법 · {policy.paymentMethod} (모의)</p>}
          {paidInFull ? <p className="premium-paid">납입 완료</p> : (
            <button className="primary" onClick={onPay}>모의 납입하기</button>
          )}
        </>
      ) : !sample && (
        <details className="contract-terms">
          <summary>보장 기간 및 가입 조건</summary>
          <p>{policy.from} 00:00 – {policy.to} 23:59 KST</p>
          <p>가입 당시 조건 · 체험 버전 v1</p>
          <p>보장 기간 중 확정된 근무가 당일 시작 전에 기상 사유로 취소되면 약정 기준을 적용해요.</p>
        </details>
      )}
    </article>
  );
}
