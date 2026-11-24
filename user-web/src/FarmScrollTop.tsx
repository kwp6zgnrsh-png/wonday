import { useEffect, useRef, useState } from "react";

export default function FarmScrollTop({ raised = false }: { raised?: boolean }) {
  const anchor = useRef<HTMLSpanElement>(null);
  const screen = useRef<HTMLElement | null>(null);
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const target = anchor.current?.parentElement?.querySelector<HTMLElement>(".farm-screen");
    if (!target) return;
    screen.current = target;
    const update = () => setVisible(target.scrollTop > 240);
    update();
    target.addEventListener("scroll", update, { passive: true });
    return () => target.removeEventListener("scroll", update);
  }, []);
  return <>
    <span ref={anchor} hidden />
    {visible && <button type="button" className={`farm-scroll-top${raised ? " farm-scroll-top-raised" : ""}`}
      aria-label="공고 맨 위로 이동" onClick={() => screen.current?.scrollTo({ top: 0,
        behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth" })}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m6 13 6-6 6 6M12 7v13M5 3h14" /></svg>
      <span>TOP</span>
    </button>}
  </>;
}
