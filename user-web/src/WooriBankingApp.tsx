import { useRef, useState } from "react";
import FarmIcon from "./FarmIcon";
import bankingIcon from "./assets/won-banking-icon.png";
import globalIcon from "./assets/won-global-icon.png";
import wooriSymbol from "./assets/woori-financial-symbol.ico";
import "./WooriBanking.css";

const languages = {
  ko: { home: "홈", accounts: "전체계좌", transfer: "이체", menu: "전체메뉴", hello: "안녕하세요, 이하루님", balance: "사용 가능한 잔액", remit: "해외송금", recent: "최근 거래", title: "우리WON글로벌" },
  en: { home: "Home", accounts: "Accounts", transfer: "Transfer", menu: "Menu", hello: "Hello, Haru", balance: "Available balance", remit: "Remittance", recent: "Recent activity", title: "Woori WON Global" },
  vi: { home: "Trang chủ", accounts: "Tài khoản", transfer: "Chuyển tiền", menu: "Menu", hello: "Xin chào, Haru", balance: "Số dư khả dụng", remit: "Chuyển tiền quốc tế", recent: "Giao dịch gần đây", title: "Woori WON Global" },
};
type Language = keyof typeof languages;
export default function WooriBankingApp({ global, onClose, onInsurance }: { global: boolean; onClose: () => void; onInsurance: () => void }) {
  const [page, setPage] = useState("home");
  const [language, setLanguage] = useState<Language>("ko");
  const [languageOpen, setLanguageOpen] = useState(false);
  const [hideBalance, setHideBalance] = useState(false);
  const [preview, setPreview] = useState<{ name: string; amount: string; bank: string } | null>(null);
  const [amount, setAmount] = useState("100000");
  const [currency, setCurrency] = useState("USD");
  const scroll = useRef<HTMLDivElement>(null);
  const t = languages[language];
  function navigate(next: string) { setPage(next); setPreview(null); scroll.current?.scrollTo({ top: 0, behavior: "instant" }); }
  const name = global ? t.title : "우리WON뱅킹";
  const accountCard = <section className="won-account-card" aria-label="체험 계좌">
    <div className="won-account-name"><img src={wooriSymbol} alt="" /><span>{global ? "Woori Global Account" : "우리 WON통장"}<small>체험 계좌 · 1002-•••-123456</small></span><button type="button" aria-label={hideBalance ? "잔액 보기" : "잔액 숨기기"} onClick={() => setHideBalance(!hideBalance)}>{hideBalance ? "보기" : "숨김"}</button></div>
    <p>{global ? t.balance : "출금 가능 금액"}</p><strong className="won-balance">{hideBalance ? "••••••" : "1,240,000"}<small> KRW</small></strong>
    <div className="won-account-actions"><button type="button" onClick={() => navigate("accounts")}>{global ? t.accounts : "거래내역"}</button><button type="button" onClick={() => navigate("transfer")}>{t.transfer}</button></div>
  </section>;
  const transactions = <div className="won-transactions"><h3>{t.recent}</h3>{[["농작업 급여", "+120,000원", "입금"], ["생활비", "−35,000원", "출금"]].map(([label, value, type]) => <div key={label}><span>{label}<small>11.23 · 예시 거래</small></span><strong className={type === "입금" ? "won-credit" : ""}>{value}</strong></div>)}</div>;
  return <div className={`won-app ${global ? "won-global" : "won-domestic"}`}>
    <header className="won-app-header"><button type="button" className="won-launcher-back" onClick={onClose} aria-label="앱 선택 화면으로"><FarmIcon name="chevronLeft" /></button><img src={global ? globalIcon : bankingIcon} alt="" /><strong>{name}</strong>
      {global ? <button type="button" className="won-language" onClick={() => setLanguageOpen(true)}>{language.toUpperCase()} ▾</button> : <button type="button" aria-label="전체 메뉴 열기" onClick={() => navigate("menu")}><FarmIcon name="grid" /></button>}
    </header>
    <div className="won-demo-label">체험 화면 · 실제 계좌 조회 및 금융 거래가 진행되지 않아요</div>
    <div className="won-app-scroll" ref={scroll}>
      {page === "home" && <>
        <div className="won-greeting"><p>{global ? "Banking, wherever you call home." : "나의 일상에, 우리"}</p><h2>{t.hello}</h2></div>
        {!global && <div className="won-finance-message"><span>오늘의 금융을<br /><b>한눈에 확인해 보세요.</b></span><div className="won-orbit" aria-hidden="true"><span>W</span></div></div>}
        {accountCard}
        {global ? <>
          <div className="won-recent-remit"><h3>최근 해외송금 <span>체험</span></h3><p>새로운 송금을 준비해 보세요.</p><button type="button" onClick={() => navigate("remit")}>{t.remit} 시작하기 <FarmIcon name="arrow" /></button></div>
          <div className="won-service-grid">{[["글로벌 퀵송금", "remit", "↗"], [t.remit, "remit", "◎"], ["국내이체", "transfer", "⇄"], ["환율계산기", "exchange", "₩"]].map(([label, route, icon]) => <button type="button" key={label} onClick={() => navigate(route)}><span>{icon}</span>{label}</button>)}</div>
        </> : <div className="won-quick-actions">{[["전체계좌", "accounts", "grid"], ["이체", "transfer", "arrow"], ["상품", "products", "sprout"], ["혜택", "benefits", "bookmark"]].map(([label, route, icon]) => <button type="button" key={label} onClick={() => navigate(route)}><FarmIcon name={icon as "grid" | "arrow" | "sprout" | "bookmark"} />{label}</button>)}</div>}
        <button type="button" className="won-insurance-promo" onClick={onInsurance}><span>농촌의 하루에, 든든한 안심<strong>WON데이보험 알아보기</strong></span><FarmIcon name="shield" /></button>
        {transactions}
      </>}
      {page === "accounts" && <><h2>{t.accounts}</h2>{accountCard}{transactions}</>}
      {["transfer", "remit"].includes(page) && <><h2>{page === "remit" ? t.remit : t.transfer}</h2>
        <p className="won-page-description">체험 정보만 입력해 주세요. 실제 이체나 해외송금은 진행되지 않아요.</p>
        {!preview ? <form className="won-transfer-form" onSubmit={(event) => { event.preventDefault(); const data = new FormData(event.currentTarget); setPreview({ name: String(data.get("recipient")), amount: String(data.get("amount")), bank: String(data.get("destination")) }); }}>
          <div className="won-from-account"><small>출금계좌 · 체험</small><strong>우리 WON통장</strong><span>1002-•••-123456</span></div>
          <label>{page === "remit" ? "보내는 국가" : "받는 은행"}<select name="destination">{(page === "remit" ? ["베트남", "필리핀", "인도네시아", "미국"] : ["우리은행", "KB국민은행", "신한은행", "하나은행", "NH농협은행", "카카오뱅크"]).map(item => <option key={item}>{item}</option>)}</select></label>
          <label>받는 분<input name="recipient" placeholder="체험용 이름" required maxLength={40} /></label>
          <label>보낼 금액 (KRW)<input name="amount" type="number" min={1} max={1240000} step={1} required placeholder="금액 입력" /></label>
          <button type="submit" className="won-primary">입력 내용 확인</button>
        </form> : <section className="won-preview"><span className="won-preview-check">✓</span><h3>입력 내용을 확인해 주세요</h3><dl><dt>받는 분</dt><dd>{preview.name}</dd><dt>{page === "remit" ? "국가" : "은행"}</dt><dd>{preview.bank}</dd><dt>금액</dt><dd>{Number(preview.amount).toLocaleString("ko-KR")}원</dd></dl><p>체험이 완료됐어요. 실제 송금이나 출금은 없어요.</p><button type="button" className="won-primary" onClick={() => navigate("home")}>홈으로</button><button type="button" className="won-text-action" onClick={() => setPreview(null)}>입력 수정</button></section>}
      </>}
      {page === "exchange" && <><h2>환율계산기</h2><section className="won-exchange"><p>계산 방식 확인용 예시 환율이에요.<br />실제 환율과 수수료는 반영하지 않아요.</p><label>원화 (KRW)<input type="number" min={0} value={amount} onChange={(event) => setAmount(event.currentTarget.value)} /></label><label>통화<select value={currency} onChange={(event) => setCurrency(event.currentTarget.value)}><option>USD</option><option>VND</option></select></label><strong>{((Number(amount) || 0) * (currency === "USD" ? 1 / 1400 : 18)).toLocaleString("ko-KR", { maximumFractionDigits: 2 })} {currency}</strong><small>예시: 1 USD = 1,400 KRW / 1 KRW = 18 VND</small></section></>}
      {["menu", "products", "benefits"].includes(page) && <><h2>{page === "products" ? "금융상품" : page === "benefits" ? "생활 혜택" : t.menu}</h2><div className="won-menu-list">{[[t.accounts, "accounts"], [t.transfer, "transfer"], ...(global ? [[t.remit, "remit"], ["환율계산기", "exchange"]] : []), ["WON데이보험", "insurance"], ["앱 선택 화면", "launcher"]].map(([label, route]) => <button type="button" key={route} onClick={() => route === "insurance" ? onInsurance() : route === "launcher" ? onClose() : navigate(route)}>{label}<FarmIcon name="arrow" /></button>)}</div>{page !== "menu" && <p className="won-page-description">체험 화면에서는 서비스 메뉴를 둘러볼 수 있어요. 실제 상품 가입과 혜택 신청은 제공되지 않아요.</p>}</>}
    </div>
    <nav className="won-bottom-nav" aria-label={`${name} 메뉴`}>{[[t.home, "home", "home"], [t.accounts, "accounts", "payment"], [global ? t.remit : "상품", global ? "remit" : "products", "products"], [t.menu, "menu", "grid"]].map(([label, route, icon]) => <button type="button" key={route} aria-current={page === route ? "page" : undefined} onClick={() => navigate(route)}><span aria-hidden="true">{icon === "home" ? "⌂" : icon === "payment" ? "▤" : icon === "grid" ? "☰" : "◇"}</span>{label}</button>)}</nav>
    {languageOpen && <div className="won-language-shade"><section role="dialog" aria-modal="true" aria-label="언어 선택"><header><h2>언어 선택 / Language</h2><button type="button" aria-label="언어 선택 닫기" onClick={() => setLanguageOpen(false)}>×</button></header><div>{([["ko", "한국어", "KR"], ["en", "English", "EN"], ["vi", "Tiếng Việt", "VN"]] as const).map(([key, label, code]) => <button type="button" key={key} aria-pressed={language === key} onClick={() => { setLanguage(key); setLanguageOpen(false); }}><span>{code}</span>{label}</button>)}</div><p>체험 화면에서는 주요 메뉴를 3개 언어로 제공해요.</p></section></div>}
  </div>;
}