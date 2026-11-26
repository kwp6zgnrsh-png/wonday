import wooriSymbol from '../../../src/assets/woori-financial-symbol.ico'
import { useRef, useState } from 'react'
import './BankingMenu.css'

export const groups = [
  ['편의서비스','check',['우리 WON데이 보험','우리WON모바일','우리WON지갑','모바일 신분증','투더문 공연예매','모바일 차용증','WON하는 부동산','세금돌려받기','전월세 AI지킴이','상가오피스 AI지킴이','부동산매매 AI진단기','부동산 AI가이드','WON 아르떼 갤러리','우리WON페이','우리제로페이','실손24']],
  ['이체/출금','transfer',['즉시/예약이체','다건이체','자동이체 등록/관리','이체내역조회','이체관리','더치페이','ATM/영업점 출금','착오송금반환']],
  ['계좌조회/관리','account',['전체계좌조회','해지계좌조회','계좌통합관리서비스','대출관리','퇴직연금관리','청약관리','휴면예금/보험금 찾기','연금 수급계좌 변경','오픈뱅킹관리','마이데이터관리']],
  ['외화/해외','globe',['환전주머니','위비트래블','해외송금','외화서비스 모두보기','해외계좌 사전개설']],
  ['공과금/세금','bill',['공과금/세금 납부','공과금/세금 납부내역 조회','자동납부 등록/조회','대학등록금 납부/조회','법원비용 납부/조회']],
  ['상품','account',['입출금/예금/적금','대출','퇴직연금','청약','펀드','신탁','ISA','외화상품','골드/실버','보험','개인사업자','노란우산','우리금융그룹']],
  ['카드','card',['우리카드 둘러보기','우리카드 이용내역/관리','대학학생증카드 발급','삼성카드 둘러보기']],
  ['혜택','gift',['이벤트','WON PLAY','꿀머니 무료 적립','쿠폰함','e스포츠관','우리 아이','우리 틴틴','스무살 우리','우리 직장인셀럽','우리 원더라이프','전담직원 서비스','TWO CHAIRS','금융바우처']],
  ['나눔/포용','heart',['굿윌스토어 물품 기부','AI포용채무진단','우리동네 선한가게','기부금']],
  ['증명서/서류','document',['증명서/공공서류 발급','전자문서지갑','우리 위임장 서비스']],
  ['영업점/ATM','bank',['영업점/ATM 찾기','자동화기기(ATM) 한도 복원']],
  ['금융업무지원','document',['고객확인등록(KYC)','본인확인서(FATCA/CRS)','국민주택채권 매입/조회','수표조회']],
  ['고객지원','help',['고객센터','분실신고','보이스피싱 피해예방','금융소비자정보','수어상담']],
] as const

function MenuIcon({kind}:{kind:string}) {
  const paths:Record<string,string>={check:'m6 12 4 4 8-8M12 2l3 2 4 1 1 4 2 3-2 3-1 4-4 1-3 2-3-2-4-1-1-4-2-3 2-3 1-4 4-1z',transfer:'M4 8h16m-4-4 4 4-4 4M20 16H4m4-4-4 4 4 4',account:'M3 6h18v13H3zM3 10h18M6 15h4',globe:'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20M2 12h20M12 2q-8 10 0 20 8-10 0-20',bill:'M5 3h14v18l-3-2-4 2-4-2-3 2zM8 8h8M8 12h8',card:'M5 3h14v18H5zM9 6v4',gift:'M3 8h18v5H3zM5 13v8h14v-8M12 8v13M12 8C3 8 7 0 12 8c5-8 9 0 0 0',heart:'M12 20 3 11C-1 3 9 1 12 7c3-6 13-4 9 4z',document:'M5 3h10l4 4v14H5zM8 11h8M8 15h6M15 3v5h4',bank:'m2 8 10-6 10 6zM5 10v9m7-9v9m7-9v9M2 22h20',help:'M8 8a4 4 0 1 1 6 3l-2 2v2M12 19h.01'}
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[kind]||paths.document}/></svg>
}

export default function BankingMenu({name,onClose,onLogout,onSelect}:{name:string;onClose:()=>void;onLogout:()=>void;onSelect:(title:string)=>void}) {
  const [query,setQuery]=useState(''),[expanded,setExpanded]=useState(false),[active,setActive]=useState<string>(groups[0][0]),[recent,setRecent]=useState(['마이데이터관리'])
  const [notice,setNotice]=useState('')
  const scroller=useRef<HTMLDivElement>(null), sections=useRef<Record<string,HTMLElement|null>>({}), pills=useRef<Record<string,HTMLButtonElement|null>>({})
  const jump=(title:string)=>{setQuery('');setExpanded(false);setActive(title);requestAnimationFrame(()=>{const section=sections.current[title],container=scroller.current;if(section&&container)container.scrollTo({top:section.offsetTop-container.offsetTop,behavior:'smooth'});pills.current[title]?.scrollIntoView({block:'nearest',inline:'center',behavior:'smooth'})})}
  const select=(title:string)=>{setRecent(items=>[title,...items.filter(item=>item!==title)].slice(0,4));if(title === '우리 WON데이 보험'||title==='이체내역조회')onSelect(title);else setNotice(title)}
  const filtered=groups.map(([title,icon,items])=>({title,icon,items:items.filter(item=>!query.trim()||`${title} ${item}`.toLowerCase().includes(query.trim().toLowerCase()))})).filter(group=>group.items.length)
  return <div className="bank-menu-page">
    <div className="bank-menu-fixed"><header className="bank-menu-header"><button className="bank-menu-back" aria-label="홈으로 돌아가기" onClick={onClose}><svg viewBox="0 0 24 24"><path d="m15 4-8 8 8 8"/></svg></button><strong>{name||'김우리'}님</strong><button className="bank-menu-logout" onClick={onLogout}>로그아웃</button><button className="bank-menu-settings" aria-label="설정" onClick={()=>setNotice('설정')}>⚙</button></header>
      <label className="bank-menu-search"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"><circle cx="10" cy="10" r="7"/><path d="m15 15 6 6"/></svg><input value={query} onChange={e=>setQuery(e.target.value)} placeholder="메뉴, 상품 등을 검색해 보세요" aria-label="전체 서비스 검색"/>{query&&<button onClick={()=>setQuery('')} aria-label="검색어 지우기">×</button>}</label>
      <div className={`bank-menu-categories ${expanded?'expanded':''}`}><div>{groups.map(([title])=><button key={title} ref={el=>{pills.current[title]=el}} className={active===title?'active':''} onClick={()=>jump(title)}>{title}</button>)}</div><button className="bank-menu-expand" aria-label={expanded?'분류 접기':'전체 분류 보기'} aria-expanded={expanded} onClick={()=>setExpanded(!expanded)}><span className="bank-category-triangle" aria-hidden="true"/></button></div>
    </div>
    <div className="bank-menu-content" ref={scroller} onScroll={()=>{const root=scroller.current;if(!root)return;let current:string=groups[0][0];for(const [title] of groups){const el=sections.current[title];if(el&&el.offsetTop-root.offsetTop-root.scrollTop<=32)current=title}if(current!==active){setActive(current);pills.current[current]?.scrollIntoView({block:'nearest',inline:'center'})}}}>
      {!query&&<section className="bank-menu-intro"><div className="bank-menu-recent-heading"><span>최근/즐겨찾기</span><button onClick={()=>setNotice('최근/즐겨찾기')}>더보기 ›</button></div><div className={`bank-menu-recent ${recent.length === 0 ? 'is-empty' : ''}`}>{recent.map(item=><div key={item}><button onClick={()=>select(item)}>☆ {item}</button><button aria-label={`${item} 최근 항목 삭제`} onClick={()=>setRecent(recent.filter(x=>x!==item))}>×</button></div>)}</div><div className="bank-menu-shortcuts">{[['check','인증/보안'],['document','증명서/서류'],['globe','환전주머니'],['help','전담직원']].map(([icon,title])=><button key={title} onClick={()=>select(title)}><MenuIcon kind={icon}/><span>{title}</span></button>)}</div><div className="bank-menu-featured">{[['document','모바일 신분증','신분증 필요할 땐, 모바일 신분증으로','새로나온'],['gift','꿀머니 혜택','꿀머니 확인하고, 그룹사 혜택도!','자주찾는']].map(([icon,title,desc,badge])=><button key={title} onClick={()=>select(title)}><MenuIcon kind={icon}/><div><strong>{title}</strong><p>{desc}</p></div><small>{badge}</small></button>)}</div></section>}
      {filtered.map(({title,icon,items})=><section className="bank-menu-section" key={title} ref={el=>{sections.current[title]=el}}><h2><MenuIcon kind={icon}/>{title}</h2><div>{items.map(item=><button key={item} className={item==='우리 WON데이 보험'?'bank-menu-insurance':''} onClick={()=>select(item)}>{item==='우리 WON데이 보험'&&<img className="bank-menu-woori" src={wooriSymbol} alt="우리은행"/>}<span>{item}</span>{item==='우리 WON데이 보험'&&<span className="bank-menu-link">›</span>}</button>)}</div></section>)}
      {!filtered.length&&<p className="bank-menu-empty">검색 결과가 없어요.<br/>다른 검색어로 찾아보세요.</p>}
    </div>{notice&&<div className="bank-service-notice-backdrop" onClick={()=>setNotice('')}><section className="bank-service-notice" role="dialog" aria-modal="true" aria-label={notice} onClick={event=>event.stopPropagation()} onKeyDown={event=>{if(event.key==='Escape')setNotice('')}}><button className="bank-service-notice-close" aria-label="닫기" onClick={()=>setNotice('')}>×</button><h2>{notice}</h2><p>{name||'김우리'}님, 로그인된 체험 화면이에요.<br/>실제 금융 거래는 연결되지 않아요.</p><button className="bank-service-notice-confirm" autoFocus onClick={()=>setNotice('')}>확인</button></section></div>}<button className="bank-menu-top" aria-label="맨 위로" onClick={()=>scroller.current?.scrollTo({top:0,behavior:'smooth'})}>↑</button>
  </div>
}
