const categories = [
  { name: "질병", icon: "✚" },
  { name: "상해", icon: "🩹" },
  { name: "치아", icon: "🦷" },
  { name: "교통", icon: "🚦" },
  { name: "펫", icon: "🐾" },
  { name: "해외여행", icon: "✈" },
  { name: "자동차", icon: "🚘" },
  { name: "기후", icon: "☂" },
] as const;

export type ClaimCategory = (typeof categories)[number]["name"];

export default function ClaimGuide({ category, onChange }: {
  category: ClaimCategory;
  onChange: (category: ClaimCategory) => void;
}) {
  return (
    <section className="claim-guide" aria-labelledby="claim-guide-title">
      <div className="claim-guide-heading">
        <h2 id="claim-guide-title">보험금 청구</h2>
        <p>사고 유형을 선택하고 접수에 필요한 정보를 확인하세요.</p>
      </div>
      <div className="claim-type-grid" role="group" aria-label="사고 유형">
        {categories.map(({ name, icon }) => (
          <button key={name} type="button" aria-pressed={category === name} onClick={() => onChange(name)}>
            <span className="claim-type-icon" aria-hidden="true">{icon}</span>
            <span>{name}</span>
          </button>
        ))}
      </div>
      <section className="claim-guide-info" aria-live="polite">
        {category === "기후" ? (
          <>
            <h3>기후 보험금 청구 안내</h3>
            <p>기상 악화로 당일 작업 시작 전에 확정 근무가 취소된 경우, 해당 근무를 골라 접수할 수 있어요.</p>
            <dl>
              <div><dt>접수할 때</dt><dd>{agriworkName}의 취소 근무를 선택해 주세요.</dd></div>
              <div><dt>심사할 때</dt><dd>취소 사유와 시각, 기상 관측자료를 확인해요.</dd></div>
            </dl>
          </>
        ) : (
          <>
            <h3>{category} 보험금 청구</h3>
            <p>이 유형의 필요서류 안내와 접수는 준비 중이에요.</p>
          </>
        )}
      </section>
    </section>
  );
}
import { agriworkName } from "./agriworkBrand";

