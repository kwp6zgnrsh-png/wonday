import { useRef, useState } from "react";

const productNames = ["든든한 하루", "차곡차곡 안심"];

export default function InsuranceProducts({ onSelect }: { onSelect: (product: string) => void }) {
  const track = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(0);

  function moveTo(index: number) {
    const list = track.current;
    const card = list?.children[index];
    const first = list?.firstElementChild;
    if (!list || !card || !first) return;
    list.scrollTo({ left: card.getBoundingClientRect().left - first.getBoundingClientRect().left });
  }

  function updatePosition(list: HTMLDivElement) {
    const left = list.getBoundingClientRect().left;
    let nearest = 0;
    let distance = Infinity;
    Array.from(list.children).forEach((card, index) => {
      const gap = Math.abs(card.getBoundingClientRect().left - left);
      if (gap < distance) {
        distance = gap;
        nearest = index;
      }
    });
    setActive(nearest);
  }

  return (
    <section className="insurance-products" aria-labelledby="insurance-products-title">
      <div className="product-heading">
        <h2 id="insurance-products-title">보험 상품</h2>
        <div className="product-controls">
          <span className="product-position" role="status" aria-label="현재 보험 상품">
            <strong>{active + 1}</strong> / {productNames.length}
          </span>
          <div className="product-arrows">
            <button type="button" aria-label="이전 상품" aria-controls="insurance-product-track"
              disabled={active === 0} onClick={() => moveTo(active - 1)}>
              <span aria-hidden="true">‹</span>
            </button>
            <button type="button" aria-label="다음 상품" aria-controls="insurance-product-track"
              disabled={active === productNames.length - 1} onClick={() => moveTo(active + 1)}>
              <span aria-hidden="true">›</span>
            </button>
          </div>
        </div>
      </div>
      <p className="product-intro">근무 확정 전에도 가입할 수 있어요.</p>
      <div className="product-carousel">
        <div id="insurance-product-track" className="product-track" ref={track}
          role="region" aria-label="좌우로 넘기는 보험 상품 목록" tabIndex={0}
          onScroll={(event) => updatePosition(event.currentTarget)}
          onKeyDown={(event) => {
            if (event.target !== event.currentTarget) return;
            const destinations: Partial<Record<string, number>> = {
              ArrowLeft: Math.max(0, active - 1),
              ArrowRight: Math.min(productNames.length - 1, active + 1),
              Home: 0,
              End: productNames.length - 1,
            };
            const target = destinations[event.key];
            if (target === undefined) return;
            event.preventDefault();
            moveTo(target);
          }}>
          {productNames.map((product, index) => (
            <article className={`card product ${index ? "mint" : "lavender"}`} key={product} aria-label={product}>
              <div className="product-card-top">
                <span className="badge">농작업 당일 취소 보장</span>
                <span className="product-symbol" aria-hidden="true">{index ? "✦" : "✳"}</span>
              </div>
              <h3>{product}</h3>
              <dl>
                <dt>상품 유형</dt><dd>기상지수형</dd>
                <dt>보험료 예시</dt><dd>월 9,900원</dd>
                <dt>보장 기간</dt><dd>30일</dd>
              </dl>
              <button className="primary" onClick={() => onSelect(product)}>
                상품 자세히 보기 <span aria-hidden="true">↗</span>
              </button>
            </article>
          ))}
        </div>
      </div>
      <p className="fine">보험료와 보장 조건은 화면 체험용 예시입니다.</p>
    </section>
  );
}
