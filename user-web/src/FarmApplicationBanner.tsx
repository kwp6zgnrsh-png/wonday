import wooriSymbol from "./assets/woori-financial-symbol.ico";
import FarmIcon from "./FarmIcon";

export default function FarmApplicationBanner({ onInsurance, onDismiss }: {
  onInsurance: () => void;
  onDismiss: () => void;
}) {
  return (
    <div className="farm-application-complete">
      <div className="farm-application-intro">
        <p>선택한 날짜에 지원했어요.<br />WONDAY 보험 상품을 확인해 보세요.</p>
        <p className="farm-application-enrollment-note">근무가 확정되기 전에도 가입할 수 있어요.</p>
      </div>
      <section className="farm-wonday-banner" aria-labelledby="farm-wonday-banner-title">
        <div className="farm-wonday-brand">
          <img src={wooriSymbol} alt="" width={27} height={27} />
          <span className="insurer-wordmark">우리손해보험</span>
        </div>
        <h3 id="farm-wonday-banner-title">WONDAY<br />파라메트릭 보험</h3>
        <p className="farm-wonday-tagline">일하는 하루를 위한 기상 보험</p>
        <p className="farm-wonday-description">기상 악화로 인해 당일 근무가 취소되면 급여를 보상받을 수 있어요!</p>
        <button type="button" className="farm-wonday-action" onClick={onInsurance}>
          WONDAY 보험 알아보기 <FarmIcon name="arrow" />
        </button>
      </section>
      <button type="button" className="secondary" onClick={onDismiss}>내 지원 내역 확인</button>
    </div>
  );
}
