import TransferHistory from './TransferHistory'
import wooriSymbol from '../../../src/assets/woori-financial-symbol.ico'
import BankingMenu from './BankingMenu'
import { useRef, useState } from 'react'
import './BankingHome.css'

function Symbol({kind}:{kind:string}) {
  const paths:Record<string,string>={wallet:'M5 7V4h13M4 8h16v13H4zM16 13h4',bell:'M6 17h12l-2-4V8a4 4 0 0 0-8 0v5zM10 21h4',menu:'M4 6h16M4 12h16M4 18h16',home:'m3 11 9-8 9 8v10H3z',chart:'M4 20V4h16v16zM7 15l4-5 3 2 4-5',bag:'M5 6h14v15H5zM9 6V3h6v3M9 10q3 5 6 0',pie:'M11 3a9 9 0 1 0 10 10H11zM15 2v7h7z',gift:'M3 8h18v5H3zM5 13v8h14v-8M12 8v13M12 8C3 8 7 0 12 8c5-8 9 0 0 0',ai:'M7 20 3 22l1-6a9 9 0 1 1 3 4'}
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[kind]||paths.wallet}/>{kind==='ai'&&<text x="7" y="15" stroke="none" fill="currentColor" fontSize="8">AI</text>}</svg>
}
export default function BankingHome({name,account,onLogout}:{name:string;account:string;onLogout:()=>void}) {
  const [easy,setEasy]=useState(false),[hidden,setHidden]=useState(false),[promo,setPromo]=useState(true),[guide,setGuide]=useState(true),[panel,setPanel]=useState(''),[tab,setTab]=useState('홈')
  const scroll=useRef<HTMLDivElement>(null)
  const [menuOpen,setMenuOpen]=useState(false)
  const open=(title:string)=>{if(title==='전체메뉴')setMenuOpen(true);else setPanel(title)}
  const [historyOpen,setHistoryOpen]=useState(false)
  if(historyOpen)return <TransferHistory onClose={()=>setHistoryOpen(false)}/>
  if(menuOpen)return <BankingMenu name={name} onClose={()=>setMenuOpen(false)} onLogout={onLogout} onSelect={title=>{if(title==='이체내역조회'){setHistoryOpen(true);return}if(title==='우리 WON데이 보험')window.parent.postMessage({type:'wonday:open-insurance'},window.location.origin);}} />
  return <div className={`won-reference-home ${easy?'easy':''}`}>
    <header className="bank-home-header"><div className="bank-mode"><button className={!easy?'active':''} onClick={()=>setEasy(false)}>일반홈</button><button className={easy?'active':''} onClick={()=>setEasy(true)}>쉬운</button></div><div className="bank-header-actions">{[['wallet','지갑'],['ai','AI 상담'],['bell','알림'],['menu','전체메뉴']].map(([kind,label])=><button key={kind} className={kind === 'menu' ? 'bank-menu-invite' : undefined} aria-label={label} onClick={()=>open(label)}><Symbol kind={kind}/>{kind==='bell'&&<i/>}</button>)}</div></header>
    <div className="bank-home-scroll" ref={scroll}>
      <section className="bank-hero">
        {promo&&<div className="bank-promo"><span className="gift-tile"><Symbol kind="gift"/></span><strong>바로 받는 간식 총 2만개<br/>소진되기 전에 응모해요!</strong><button className="bank-dismiss" aria-label="이벤트 안내 닫기" onClick={()=>setPromo(false)}>×</button></div>}
        <div className="bank-scene" aria-hidden="true"><div className="scene-window"/><div className="scene-lamp"/><span className="scene-plant">✦</span><div className="scene-bear"><span className="bear-ear left"/><span className="bear-ear right"/><span className="bear-face">• ᴥ •</span></div><div className="scene-friend">•ᴗ•</div><div className="scene-blanket"/><span className="scene-ball">◉</span><div className="scene-dots"><b/><i/><i/></div></div>
        <div className="bank-scene-controls"><button onClick={()=>open('휴계좌 설정')}>⚙ 휴계좌설정</button><button onClick={()=>setHidden(!hidden)}>잔액숨김 <span className={`bank-switch ${hidden?'on':''}`}/></button></div>
        {guide&&<div className="bank-account-guide"><button className="bank-dismiss" aria-label="계좌 안내 닫기" onClick={()=>setGuide(false)}>×</button><p>홈 화면에 다른 계좌를 추가하거나<br/>계좌 순서를 변경할 수 있어요</p><button onClick={()=>open('홈 계좌 설정')}>홈 계좌 설정 ›</button><div className="mini-accounts" aria-hidden="true"><span>● ━━━━━</span><span>● ━━━━━</span><span>● ━━━━━</span></div></div>}
        <section className="bank-balance-card"><div className="bank-account-top"><span className="bank-account-logo"><img src={wooriSymbol} alt="우리은행"/></span><div><h2>WON 통장</h2><p>우리 {account?`••••-${account.slice(-4)}`:'1002-•••-••0370'} <button className="bank-account-copy" aria-label="계좌번호 확인" onClick={()=>open('계좌 정보')}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><rect x="8" y="8" width="12" height="13" rx="2"/><path d="M15 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/></svg></button></p></div><button aria-label="계좌 메뉴" onClick={()=>open('계좌 관리')}>⋮</button></div><div className="bank-money"><strong>{hidden?'금액 숨김':'1,899원'}</strong><button onClick={()=>open('이체')}>이체</button></div><small className="bank-demo">체험용 계좌 · 예시 잔액</small><button className="bank-salary" onClick={()=>open('첫 급여 혜택')}>↪　첫 급여받으면 NpayP 100% 드림</button></section>
        <button className="bank-transfer-history-shortcut" onClick={()=>setHistoryOpen(true)}>이체 내역 확인 <span aria-hidden="true">›</span></button><button className="bank-all-accounts" onClick={()=>open('전체계좌보기')}>전체계좌보기</button>
      </section>
      <section className="bank-lower"><button className="bank-event" onClick={()=>open('이벤트')}><strong>올해의 마지막 카운트다운<br/>함께할 준비됐나요?</strong><span>투더문에서 예매하기</span><b>COUNTDOWN<br/>FANTASY<br/><small>2026 ✦ 2027</small></b></button>
      <h2>추천서비스</h2><div className="bank-recommend-carousel">{[[['▣','우리WON모바일','우리은행이 만든 알뜰한 통신'],['↗','환전주머니','여행 환전을 손쉽게 준비하기'],['▰','우리WON지갑','국민비서 등 유용한 생활 서비스']],[['♡','우리 365','매일 더 가까운 금융'],['%','혜택 모아보기','나에게 맞는 혜택을 한눈에'],['▣','ATM 찾기','가까운 영업점과 ATM']]].map((items,i)=><div className="bank-recommend" key={i}>{items.map(([icon,title,desc])=><button key={title} onClick={()=>open(title)}><span>{icon}</span><div><strong>{title}</strong><p>{desc}</p></div></button>)}</div>)}</div>
      <h2>우리금융그룹 서비스</h2><div className="bank-group-carousel">{[['보험','나를 지켜주는','든든한 보장','☂'],['캐피탈','내 자동차','대출 한도','🚙'],['증권','새로운','투자의 시작','↗']].map(([title,a,b,icon])=><button key={title} onClick={()=>open(title)}><strong>{title}</strong><p>{a}<br/>{b}</p><span>{icon}</span></button>)}</div><button className="bank-notice" onClick={()=>open('공지사항')}><b>공지</b> 우리WON뱅킹 겨울 시즌 아이콘 적용 안내 <span>›</span></button></section>
    </div>
    <nav className="bank-bottom-nav">{[['bag','상품'],['pie','자산·소비'],['home','홈'],['chart','주식'],['gift','혜택']].map(([kind,label])=><button key={label} className={tab===label?'active':''} onClick={()=>{setTab(label);if(label==='홈')scroll.current?.scrollTo({top:0,behavior:'smooth'});else open(label)}}><Symbol kind={kind}/><span>{label}</span></button>)}</nav>
    {panel&&<div className="bank-panel-backdrop" onClick={()=>setPanel('')}><section className="bank-home-panel" role="dialog" aria-modal="true" aria-label={panel} onClick={e=>e.stopPropagation()}><button className="bank-dismiss" aria-label="닫기" onClick={()=>setPanel('')}>×</button><h2>{panel}</h2><p>{name||'김우리'}님, 로그인된 체험 화면이에요.</p><p>실제 금융 거래는 연결되지 않아요.</p>{panel==='전체메뉴'&&<button onClick={onLogout}>로그아웃</button>}<button className="bank-panel-confirm" onClick={()=>setPanel('')}>확인</button></section></div>}
  </div>
}
