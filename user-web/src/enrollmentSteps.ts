export const enrollmentStages = [
  {
    title: "계정 연동·본인 확인",
    steps: ["개인정보 동의", "간편 인증"],
  },
  {
    title: "가입 정보 확인",
    steps: ["상세 동의", "계약 관계 설정", "가입 내용 확인"],
  },
  {
    title: "가입 서류·약관 확인",
    steps: ["청약 서류", "상품설명서 주요 내용", "보험 약관", "가입내용 최종 확인"],
  },
  {
    title: "결제",
    steps: ["결제 방법 확인"],
  },
];

export const enrollmentSteps = enrollmentStages.flatMap(({ steps }, stageIndex) =>
  steps.map((title) => ({ title, stageIndex })),
);
