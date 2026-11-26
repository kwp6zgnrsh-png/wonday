import { useEffect, useRef } from "react";
import "./WooriBanking.css";

export default function WooriBankingApp({ global, onClose, onInsurance }: {
  global: boolean;
  onClose: () => void;
  onInsurance?: () => void;
}) {
  const frame = useRef<HTMLIFrameElement>(null);
  useEffect(() => {
    const receive = (event: MessageEvent) => {
      if (event.origin === window.location.origin && event.source === frame.current?.contentWindow && event.data?.type === "wonday:open-insurance") onInsurance?.();
    };
    window.addEventListener("message", receive);
    return () => window.removeEventListener("message", receive);
  }, [onInsurance]);
  return <div className="won-imported-onboarding">
    <div className="won-imported-toolbar">
      <button type="button" onClick={onClose}>‹ 앱 선택 화면</button>
      <span>{global ? "우리WON글로벌 · 이용 등록 체험" : "우리WON뱅킹 · 이용 등록 체험"}</span>
    </div>
    <iframe ref={frame} title="우리WON뱅킹 최초 이용 등록" src="/won-banking-onboarding/index.html" />
  </div>;
}
