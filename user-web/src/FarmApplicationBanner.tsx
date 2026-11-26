import wooriSymbol from "./assets/woori-financial-symbol.ico";
import FarmIcon from "./FarmIcon";

export default function FarmApplicationBanner({ onInsurance, onDismiss, handoff = false, english = false }: {
  english?: boolean;
  handoff?: boolean;
  onInsurance: () => void;
  onDismiss: () => void;
}) {
  return (
    <div className="farm-application-complete">
      <div className="farm-application-intro">
        <p>{!handoff && <>선택한 날짜에 지원했어요.<br /></>}{english ? "Explore WONday insurance." : "WONDAY 보험 상품을 확인해 보세요."}</p>
        <p className="farm-application-enrollment-note">{english ? "You can enroll before your work is confirmed." : "근무가 확정되기 전에도 가입할 수 있어요."}</p>
      </div>
      <section className="farm-wonday-banner" aria-labelledby="farm-wonday-banner-title">
        <div className="farm-wonday-brand">
          <img src={wooriSymbol} alt="" width={27} height={27} />
          <span className="insurer-wordmark">{english ? "Woori WON Banking" : "우리 WON뱅킹"}</span>
        </div>
        <h3 id="farm-wonday-banner-title">WONDAY<br />{english ? "Parametric Insurance" : "파라메트릭 보험"}</h3>
        <p className="farm-wonday-tagline">{english ? "Peace of mind for rural working days affected by weather." : "날씨에 흔들리는 농촌의 하루에, 든든한 안심을"}</p>
        <p className="farm-wonday-description">{english ? "Get wage compensation if severe weather cancels your work on the day!" : "기상 악화로 인해 당일 근무가 취소되면 급여를 보상받을 수 있어요!"}</p>
        <button type="button" className="farm-wonday-action" onClick={onInsurance}>
          {english ? "Explore WONday Insurance" : "WONDAY 보험 알아보기"} <FarmIcon name="arrow" />
        </button>
      </section>
      <button type="button" className="secondary" onClick={onDismiss}>{english ? "Cancel" : handoff ? "취소" : "내 지원 내역 확인"}</button>
    </div>
  );
}
