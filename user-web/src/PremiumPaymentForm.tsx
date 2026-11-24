import { useState } from "react";
import { initialInstallment, paymentMethods } from "./insurancePolicies";
import type { PaymentMethod, Policy } from "./insurancePolicies";

export default function PremiumPaymentForm({ policy, onConfirm }: {
  policy: Policy;
  onConfirm: (method: PaymentMethod) => void;
}) {
  const [method, setMethod] = useState<PaymentMethod | undefined>(policy.paymentMethod);
  const { monthlyPremium, paidInstallments } = policy.installments ?? initialInstallment;

  return (
    <form onSubmit={(event) => {
      event.preventDefault();
      if (method) onConfirm(method);
    }}>
      <div className="premium-total">
        <div><strong>{policy.name}</strong><p>{paidInstallments + 1}회차 보험료</p></div>
        <strong>{monthlyPremium.toLocaleString("ko-KR")}원</strong>
      </div>
      <fieldset className="payment-options">
        <legend>납입 방법</legend>
        {paymentMethods.map((option) => (
          <label className="check-row" key={option}>
            <input type="radio" name="premium-method" value={option} checked={method === option}
              onChange={() => setMethod(option)} required />
            {option}
          </label>
        ))}
      </fieldset>
      <p className="fine">선택한 방법으로 모의 납입을 기록해요.</p>
      <button className="primary" disabled={!method}>선택한 방법으로 모의 납입</button>
    </form>
  );
}
