type Installments = {
  monthlyPremium: number;
  paidInstallments: number;
  totalInstallments: number;
};

export const paymentMethods = ["카드 결제", "계좌이체", "간편결제"] as const;
export type PaymentMethod = (typeof paymentMethods)[number];

export type Policy = {
  name: string;
  from: string;
  to: string;
  installments?: Installments;
  paymentMethod?: PaymentMethod;
};

export const initialInstallment: Installments = {
  monthlyPremium: 9900,
  paidInstallments: 1,
  totalInstallments: 1,
};

export const samplePolicies: Policy[] = [
  {
    name: "든든한 하루",
    from: "",
    to: "",
    installments: { monthlyPremium: 9900, paidInstallments: 6, totalInstallments: 12 },
  },
  {
    name: "차곡차곡 안심",
    from: "",
    to: "",
    installments: { monthlyPremium: 9900, paidInstallments: 3, totalInstallments: 12 },
  },
];
