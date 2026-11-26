import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import Illustration from './Illustration'
import BankingHome from './BankingHome'

const steps = ['welcome', 'consent', 'identity', 'sms', 'guide', 'id-select', 'id-capture', 'face', 'terms', 'account', 'deposit', 'pin', 'pattern', 'biometric', 'customer', 'withdrawal', 'complete'] as const
type Step = typeof steps[number]
const titles = ['시작하기', '본인확인', '본인확인', '본인확인', '시작하기', '비대면 실명확인', '신분증 확인', '비대면 실명확인', '시작하기', '계좌인증', '계좌인증', '우리WON인증서', '우리WON인증서', 'Face 등록', '고객정보 등록', '출금계좌 등록', '이용 등록 완료']
const groups = [0, 1, 4, 8, 11, 14, 16]
const groupNames = ['서비스 소개', '휴대폰 본인확인', '비대면 실명확인', '약관 동의 · 계좌인증', '인증수단 등록', '고객정보 · 출금계좌', '이용 준비 완료']
const slides = [
  { title: <>내 모든 금융생활의 중심<br/><strong>우리 WON 뱅킹</strong></>, sub: '일상에 필요한 금융을 한곳에서', kind: 'won' as const },
  { title: <>중요한 금융 일정을<br/><strong>놓치지 않도록</strong></>, sub: '나에게 필요한 소식을 편리하게', kind: 'message' as const },
  { title: <>모든 금융회사의 계좌를<br/><strong>한곳에서 관리</strong></>, sub: '흩어진 금융도 간편하게 모아보세요', kind: 'id' as const },
  { title: <>나의 소중한 금융생활을<br/><strong>더 쉽고 안전하게</strong></>, sub: '우리WON인증서로 간편하게', kind: 'shield' as const },
]
function Icon({ name, size = 22 }: { name: string; size?: number }) {
  const paths: Record<string, ReactNode> = {
    back: <path d="m14 5-7 7 7 7"/>, close: <path d="m6 6 12 12M18 6 6 18"/>, chevron: <path d="m9 5 7 7-7 7"/>,
    check: <path d="m5 12 4 4L19 6"/>, home: <><path d="m3 10 9-7 9 7v11H3Z"/><path d="M9 21v-8h6v8"/></>,
    gift: <><rect x="4" y="9" width="16" height="12" rx="2"/><path d="M3 9h18M12 6v15"/><path d="M12 7C4 8 5 0 9 3l3 4c8 1 7-7 3-4Z"/></>,
    chart: <><path d="M4 3v18h17M8 15l4-5 4 3 5-7"/></>, wallet: <><rect x="3" y="5" width="18" height="15" rx="3"/><path d="M16 10h5v6h-5Z"/></>,
    menu: <path d="M4 6h16M4 12h16M4 18h16"/>, lock: <><rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/></>,
    person: <><circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/></>, phone: <><rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18h4"/></>,
    card: <><rect x="2" y="5" width="20" height="15" rx="3"/><path d="M2 10h20M6 15h4"/></>, eye: <><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></>,
  }
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name] ?? paths.card}</svg>
}
function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return <div className="field"><label>{label}{children}</label>{hint && <p className="field-hint">{hint}</p>}</div>
}
function Modal({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => { const dialog = ref.current!; dialog.showModal(); return () => dialog.close() }, [])
  return <dialog ref={ref} aria-label={title} className="sheet" onCancel={onClose} onClick={e => { if (e.target === e.currentTarget) onClose() }}><div className="sheet-handle"/><div className="sheet-heading"><h2>{title}</h2><button className="icon-button" onClick={onClose} aria-label="닫기"><Icon name="close"/></button></div>{children}</dialog>
}
function getStep(): Step { const key = window.location.hash.slice(1); return steps.includes(key as Step) ? key as Step : 'welcome' }

export default function App() {
  const [step, setStep] = useState<Step>(getStep)
  const [slide, setSlide] = useState(0)
  const [signedIn, setSignedIn] = useState(false)
  useEffect(() => {
    if (step !== 'complete' || signedIn) return
    const timer = window.setTimeout(() => setSignedIn(true), 1000)
    return () => window.clearTimeout(timer)
  }, [step, signedIn])

  const [autoPreview, setAutoPreview] = useState(false)
  const [checks, setChecks] = useState<Record<string, boolean>>({})
  const [name, setName] = useState('')
  const [nationality, setNationality] = useState('내국인')
  const [birth, setBirth] = useState('')
  const [phone, setPhone] = useState('')
  const [carrier, setCarrier] = useState('')
  const [sms, setSms] = useState('')
  const [seconds, setSeconds] = useState(180)
  const [bank, setBank] = useState('우리은행')
  const [account, setAccount] = useState('')
  const [deposit, setDeposit] = useState('')
  const [idKind, setIdKind] = useState('신분증')
  const [pin, setPin] = useState('')
  const [firstPin, setFirstPin] = useState('')
  const [pattern, setPattern] = useState<number[]>([])
  const [english, setEnglish] = useState('')
  const [email, setEmail] = useState('')
  const [address, setAddress] = useState('')
  const [detail, setDetail] = useState('')
  const [modal, setModal] = useState<string | null>(null)
  const [error, setError] = useState('')
  const contentRef = useRef<HTMLElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const index = steps.indexOf(step)
  const group = groups.findLastIndex(start => index >= start)
  const navigate = (next: Step) => { if (next !== step) { setStep(next); setError(''); window.history.replaceState(null, '', `#${next}`) } }
  const next = () => navigate(steps[Math.min(index + 1, steps.length - 1)])
  useEffect(() => {
    if (!autoPreview || modal || step === 'welcome') return
    const frames: Array<() => void> = []
    const typeText = (text: string, update: (value: string) => void) => {
      update('')
      for (let index = 1; index <= text.length; index++) frames.push(() => update(text.slice(0, index)))
    }
    if (step === 'consent') { setChecks({}); for (const key of ['smsService', 'smsPrivacy']) frames.push(() => setChecks(previous => ({ ...previous, [key]: true }))) }
    if (step === 'identity') { setNationality('내국인'); setCarrier('SKT'); typeText('김우리', setName); typeText('950101', setBirth); typeText('01000000000', setPhone) }
    if (step === 'sms') typeText('123456', setSms)
    if (step === 'terms') { for (const key of ['cert', 'privacy', 'unique']) frames.push(() => setChecks(previous => ({ ...previous, [key]: true }))) }
    if (step === 'account') { setBank('우리은행'); typeText('1002000012345', setAccount) }
    if (step === 'deposit') typeText('1234', setDeposit)
    if (step === 'pin') { setFirstPin(''); typeText('123456', setPin) }
    if (step === 'pattern') { setPattern([]); for (const point of [0, 1, 4, 7, 8]) frames.push(() => setPattern(previous => [...previous, point])) }
    if (step === 'customer') { typeText('KIM WOORI', setEnglish); typeText('woori@example.com', setEmail); typeText('서울특별시 중구 세종대로 00', setAddress); typeText('가상빌딩 101호', setDetail) }
    if (step === 'withdrawal') frames.push(() => setChecks(previous => ({ ...previous, withdrawal: true })))
    const inputTimers = frames.map((frame, index) => window.setTimeout(frame, 100 + (index / Math.max(frames.length, 1)) * 650))
    const timer = window.setTimeout(() => {
      if (step === 'complete') {
        setAutoPreview(false)
        setSignedIn(true); 
      } else {
        const following = steps[Math.min(steps.indexOf(step) + 1, steps.length - 1)]
        setStep(following); setError('')
        window.history.replaceState(null, '', `#${following}`)
      }
    }, 1000)
    return () => { window.clearTimeout(timer); inputTimers.forEach(window.clearTimeout) }
  }, [autoPreview, step, modal])
  function submitPin() {
    if (pin.length !== 6) return
    if (!firstPin) { setFirstPin(pin); setPin(''); setError('') }
    else if (firstPin !== pin) { setError('비밀번호가 일치하지 않아요. 다시 입력해 주세요.'); setPin('') }
    else { setFirstPin(''); setPin(''); navigate('pattern') }
  }
  useEffect(() => {
    if (step !== 'pin' || modal) return
    const onKey = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey || event.isComposing) return
      if (event.target instanceof HTMLElement && event.target.closest('input, textarea, select, [contenteditable="true"]')) return
      if (/^[0-9]$/.test(event.key)) { event.preventDefault(); setError(''); setPin(value => (value + event.key).slice(0, 6)) }
      else if (event.key === 'Backspace' || event.key === 'Delete') { event.preventDefault(); setError(''); setPin(value => value.slice(0, -1)) }
      else if (event.key === 'Enter') { event.preventDefault(); if (event.repeat) return; submitPin() }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })
  useEffect(() => { const handler = () => { setStep(getStep()); setError('') }; window.addEventListener('hashchange', handler); return () => window.removeEventListener('hashchange', handler) }, [])
  useEffect(() => { contentRef.current?.scrollTo(0, 0); headingRef.current?.focus() }, [step])
  useEffect(() => { if (step !== 'sms') return; const timer = window.setInterval(() => setSeconds(s => Math.max(0, s - 1)), 1000); return () => window.clearInterval(timer) }, [step])
  const toggle = (key: string) => setChecks(prev => ({ ...prev, [key]: !prev[key] }))
  const all = (keys: string[]) => keys.every(key => checks[key])
  const toggleAll = (keys: string[]) => setChecks(prev => ({ ...prev, ...Object.fromEntries(keys.map(key => [key, !keys.every(k => prev[k])])) }))
  const fillIdentity = () => { setName('김우리'); setBirth('950101'); setPhone('01000000000'); setCarrier('SKT') }
  const reset = () => { setAutoPreview(false); setChecks({}); setName(''); setBirth(''); setPhone(''); setCarrier(''); setSms(''); setAccount(''); setDeposit(''); setPin(''); setFirstPin(''); setPattern([]); setEnglish(''); setEmail(''); setAddress(''); setDetail(''); setNationality('내국인'); setSeconds(180); setBank('우리은행'); setIdKind('신분증'); setSlide(0); setModal(null); navigate('welcome') }
  const checkRow = (key: string, label: string, detailEnabled = true) => <div className="check-row" key={key}><label className="check-label"><input type="checkbox" checked={!!checks[key]} onChange={() => toggle(key)}/><span className="check-mark"><Icon name="check" size={16}/></span><span>{label}</span></label>{detailEnabled && <button className="icon-button" aria-label={`${label} 상세 보기`} onClick={() => setModal(label)}><Icon name="chevron" size={16}/></button>}</div>
  const selectCard = (icon: string, title: string, desc: string, action: () => void) => <button className="select-card" onClick={action}><span className="small-illustration"><Icon name={icon} size={25}/></span><span><strong>{title}</strong><small>{desc}</small></span><Icon name="chevron" size={18}/></button>
  let body: ReactNode
  let footer: ReactNode
  const primary = (text: string, action = next, disabled = false) => <button className="primary" onClick={action} disabled={disabled}>{text}</button>
  const heading = (text: ReactNode, description?: string) => <div className="page-title"><h1 ref={headingRef} tabIndex={-1}>{text}</h1>{description && <p>{description}</p>}</div>
  const notice = (text: string) => <div className="notice"><span className="info-symbol">i</span><p>{text}</p></div>
  const sample = (action: () => void, label = '가상 정보로 채우기') => <button className="sample-button" onClick={action}>{label}</button>

  switch (step) {
    case 'welcome':
      body = <div className="welcome-content"><div className="welcome-art" key={slide}><Illustration kind={slides[slide].kind}/></div><div className="welcome-copy" aria-live="polite"><p>{slides[slide].sub}</p><h1 ref={headingRef} tabIndex={-1}>{slides[slide].title}</h1></div><div className="carousel-dots">{slides.map((_, i) => <button key={i} className={i === slide ? 'active' : ''} aria-label={`소개 ${i + 1}`} aria-pressed={slide === i} onClick={() => setSlide(i)}/>)}</div><button className="start-button" onClick={next}>시작하기</button><button className="text-button login-link" onClick={() => setSignedIn(true)}>로그인하기</button><span className="welcome-down" aria-hidden="true">⌄</span></div>
      footer = <nav className="bottom-nav" aria-label="서비스 메뉴">{[['wallet','상품'],['chart','자산·소비'],['home','홈'],['chart','주식'],['gift','혜택']].map(([icon,label])=><button key={label} className={label === '홈' ? 'active' : ''} onClick={()=>label === '홈' ? setSlide(0) : setModal(`${label} 안내`)}><Icon name={icon}/><span>{label}</span></button>)}</nav>
      break
    case 'consent':
      body = <>{heading(<>안전한 시작을 위해<br/>본인확인을 진행할게요</>, '휴대폰 인증에 필요한 약관을 확인해 주세요.')}<Illustration kind="message"/><div className="agreement-box"><button className={`all-check ${all(['smsService','smsPrivacy']) ? 'checked' : ''}`} onClick={() => toggleAll(['smsService','smsPrivacy'])} aria-pressed={all(['smsService','smsPrivacy'])}><span className="round-check"><Icon name="check" size={17}/></span>[필수] 전체 동의하기</button>{checkRow('smsService','휴대폰 본인확인 서비스 이용약관')}{checkRow('smsPrivacy','개인정보 수집·이용 동의')}</div>{notice('이 화면은 UI 체험용입니다. 실제 인증문자가 발송되거나 개인정보가 전송되지 않습니다.')}</>
      footer = primary('동의하고 진행하기', next, !all(['smsService','smsPrivacy']))
      break
    case 'identity':
      body = <>{heading(<>본인확인을 위해<br/>정보를 입력해 주세요</>, '체험에는 가상 정보를 이용해 주세요.')}{sample(fillIdentity)}<Field label="이름"><input value={name} onChange={e=>setName(e.target.value)} placeholder="이름 입력" maxLength={30} autoComplete="off"/></Field><fieldset className="segmented"><legend>내·외국인 구분</legend>{['내국인','외국인'].map(item=><label className={nationality === item ? 'selected' : ''} key={item}><input type="radio" name="nationality" checked={nationality === item} onChange={()=>setNationality(item)}/>{item}</label>)}</fieldset><Field label="생년월일" hint="주민등록번호 대신 생년월일 6자리만 입력하는 시안입니다."><input value={birth} onChange={e=>setBirth(e.target.value.replace(/\D/g,'').slice(0,6))} inputMode="numeric" placeholder="예: 950101"/></Field><Field label="휴대폰 번호"><div className="phone-input"><select aria-label="통신사" value={carrier} onChange={e=>setCarrier(e.target.value)}><option value="">통신사</option>{['SKT','KT','LG U+','알뜰폰'].map(v=><option key={v}>{v}</option>)}</select><input aria-label="휴대폰 번호" value={phone} onChange={e=>setPhone(e.target.value.replace(/\D/g,'').slice(0,11))} inputMode="tel" placeholder="숫자만 입력"/></div></Field></>
      footer = primary('인증번호 받기', ()=>{setSeconds(180); setSms(''); next()}, !name.trim() || !/^\d{6}$/.test(birth) || !/^01\d{8,9}$/.test(phone) || !carrier)
      break
    case 'sms':
      body = <>{heading(<>문자메시지로 받은<br/>인증번호를 입력해 주세요</>, `${phone ? phone.slice(0,3)+'-****-'+phone.slice(-4) : '체험용 휴대폰'}으로 인증하는 화면입니다.`)}<div className="demo-note">체험용 인증번호 <strong>123456</strong></div><Field label="인증번호"><div className="with-trailing"><input aria-label="인증번호" aria-describedby="sms-help" value={sms} onChange={e=>setSms(e.target.value.replace(/\D/g,'').slice(0,6))} placeholder="6자리 입력" inputMode="numeric"/><span className={seconds === 0 ? 'expired' : ''}>{Math.floor(seconds/60)}:{String(seconds%60).padStart(2,'0')}</span><button onClick={()=>setSeconds(180)}>시간연장</button></div></Field><div className="align-right"><button className="text-button" onClick={()=>{setSeconds(180);setSms('');setError('인증번호를 새로 준비했어요. 체험용 번호는 123456입니다.')}}>재요청하기</button></div><p id="sms-help" className="muted">실제 문자 발송 없이 인증 과정을 체험합니다.</p></>
      footer = primary('인증하기', ()=>{if(seconds===0) setError('인증 시간이 지났어요. 재요청해 주세요.'); else if(sms!=='123456') setError('체험용 인증번호 123456을 입력해 주세요.'); else next()},sms.length!==6)
      break
    case 'guide':
      body = <>{heading(<>반가워요, {name || '고객'}님<br/>이제 시작해 볼까요?</>, '아래 순서로 진행할게요.')}<div className="journey">{[['card','비대면 실명확인','신분증을 준비해 주세요.'],['lock','인증수단 등록','나만의 비밀번호와 패턴을 등록해요.'],['person','고객정보 등록','고객님의 정보를 확인해요.'],['wallet','출금계좌 등록','이용할 계좌를 확인하면 준비 끝!']].map(([icon,title,desc],i)=><div className="journey-item" key={title}><span className={`journey-icon color-${i}`}><Icon name={icon}/></span><div><strong>{title}</strong><p>{desc}</p></div></div>)}</div>{notice('신분증 촬영과 계좌 확인은 준비된 가상 정보로 진행됩니다.')}</>
      footer = primary('신분증 확인하기')
      break
    case 'id-select':
      body = <>{heading(<>인증할 신분증을<br/>선택해 주세요</>)}<Illustration kind="id"/>{selectCard('card','신분증','주민등록증 또는 운전면허증',()=>{setIdKind('신분증');next()})}{selectCard('phone','모바일 신분증','모바일 신분증으로 확인',()=>{setIdKind('모바일 신분증');next()})}{notice('실물 신분증을 업로드하지 않습니다. 다음 화면에서 촬영 UI를 체험할 수 있어요.')}</>
      break
    case 'id-capture':
      body = <>{heading(<>{idKind}을<br/>확인해 주세요</>, '신분증이 영역 안에 들어오도록 맞춰 주세요.')}<div className="camera-demo"><span className="camera-tag">촬영 화면 미리보기</span><div className="sample-id"><span>주민등록증 <small>견본</small></span><div className="sample-id-body"><Icon name="person" size={48}/><div><strong>김우리</strong><p>950101-•••••••</p><i/><i/></div></div><small>실제 효력이 없는 시연용 이미지</small></div><span className="camera-corner tl"/><span className="camera-corner tr"/><span className="camera-corner bl"/><span className="camera-corner br"/></div>{notice('빛이 반사되지 않도록 신분증을 놓아 주세요. 체험 중에는 카메라가 실행되지 않습니다.')}</>
      footer = primary('가상 신분증으로 확인하기')
      break
    case 'face':
      body = <>{heading(<>본인확인을 위해<br/>얼굴을 확인할게요</>, '밝은 곳에서 정면을 바라봐 주세요.')}<Illustration kind="face"/><div className="tips"><p>모자와 마스크를 벗고 얼굴을 맞춰 주세요.</p><p>얼굴이 화면 안에 잘 들어오도록 해 주세요.</p></div>{notice('카메라 접근이나 얼굴 정보 수집 없이 다음 화면으로 이동합니다.')}</>
      footer = primary('얼굴 확인 체험하기')
      break
    case 'terms':
      body = <>{heading(<>서비스 이용을 위한<br/>약관에 동의해 주세요</>)}<div className="agreement-box"><button className={`all-check ${all(['cert','privacy','unique']) ? 'checked' : ''}`} aria-pressed={all(['cert','privacy','unique'])} onClick={()=>toggleAll(['cert','privacy','unique'])}><span className="round-check"><Icon name="check" size={17}/></span>[필수] 전체 동의하기</button>{checkRow('cert','우리WON인증서 서비스 이용약관')}{checkRow('privacy','개인정보 수집·이용 동의')}{checkRow('unique','고유식별정보 처리 동의')}</div><div className="agreement-box optional">{checkRow('cloud','[선택] 클라우드 저장 동의')}{checkRow('mydata','[선택] 마이데이터 서비스 가입')}{checkRow('benefit','[선택] 혜택·이벤트 안내 동의')}</div>{notice('선택 항목에 동의하지 않아도 다음 단계로 진행할 수 있어요. 약관 본문은 퍼블리싱 확인용 예시입니다.')}</>
      footer = primary('동의하고 계속하기',next,!all(['cert','privacy','unique']))
      break
    case 'account':
      body = <>{heading(<>우리WON인증서 발급을 위해<br/>계좌를 확인할게요</>, '본인 명의의 입출금 계좌를 입력해 주세요.')}{sample(()=>setAccount('1002000000000'))}<Field label="은행 / 증권사"><select value={bank} onChange={e=>setBank(e.target.value)}>{['우리은행','국민은행','신한은행','하나은행','NH농협은행','카카오뱅크'].map(v=><option key={v}>{v}</option>)}</select></Field><Field label="계좌번호"><input value={account} onChange={e=>setAccount(e.target.value.replace(/\D/g,'').slice(0,16))} inputMode="numeric" placeholder="'-' 없이 숫자만 입력"/></Field><div className="account-explainer"><span className="small-illustration"><Icon name="wallet" size={28}/></span><div><strong>1원으로 간편하게 확인해요</strong><p>입금자명에 표시된 숫자 4자리를<br/>다음 화면에서 입력해 주세요.</p></div></div>{notice('실제 계좌 조회나 1원 송금은 발생하지 않아요.')}</>
      footer = primary('1원 인증 체험하기',next,account.length<10)
      break
    case 'deposit':
      body = <>{heading(<><em>{bank} {account ? '•••• '+account.slice(-4) : '•••• 0000'}</em><br/>계좌로 1원을 보냈어요</>, '입금 내역에서 인증번호를 확인하는 화면입니다.')}<div className="deposit-card"><span>입금자명</span><strong>우리 <b>1234</b></strong><div><span>입금 금액</span><strong>1원</strong></div><small>가상 입금 내역 · 실제 송금 없음</small></div><Field label="인증번호" hint="위 가상 입금 내역의 숫자 4자리를 입력해 주세요."><input value={deposit} onChange={e=>setDeposit(e.target.value.replace(/\D/g,'').slice(0,4))} inputMode="numeric" placeholder="4자리 입력"/></Field></>
      footer = primary('인증하기',()=>deposit==='1234' ? next() : setError('가상 입금 내역의 인증번호 1234를 입력해 주세요.'),deposit.length!==4)
      break
    case 'pin':
      body = <div className="pin-page">{heading(firstPin ? <>비밀번호를<br/>한 번 더 입력해 주세요</> : <>인증서 비밀번호를<br/>등록해 주세요</>, '체험용 숫자 6자리를 입력해 주세요.')}<div className="pin-dots" aria-label={`${pin.length}자리 입력됨`}>{Array.from({length:6},(_,i)=><span key={i} className={pin.length>i?'filled':''}/>)}</div><p className="muted">실제 사용 중인 비밀번호는 입력하지 마세요.<br/>키보드 숫자 입력 · Backspace 삭제 · Enter 확인</p><div className="keypad">{['1','2','3','4','5','6','7','8','9','전체삭제','0','⌫'].map(key=><button key={key} aria-label={key==='⌫'?'한 자리 지우기':key} onClick={()=>{setError('');setPin(p=>key==='전체삭제'?'':key==='⌫'?p.slice(0,-1):(p+key).slice(0,6))}}>{key}</button>)}</div></div>
      footer = primary(firstPin ? '비밀번호 확인' : '다음',submitPin,pin.length!==6)
      break
    case 'pattern':
      body = <div className="pattern-page">{heading(<>간편하게 사용할<br/>패턴을 등록해 주세요</>, '점을 순서대로 눌러 4개 이상 연결해 주세요.')}<div className="pattern-grid"><svg viewBox="0 0 240 240" aria-hidden="true"><polyline points={pattern.map(i=>`${40+(i%3)*80},${40+Math.floor(i/3)*80}`).join(' ')} fill="none" stroke="#73adf7" strokeWidth="4" strokeLinejoin="round"/></svg>{Array.from({length:9},(_,i)=><button key={i} aria-label={`패턴 ${i+1}번 점`} aria-pressed={pattern.includes(i)} className={pattern.includes(i)?'selected':''} onClick={()=>setPattern(p=>p.includes(i)?p:[...p,i])}><span/></button>)}</div><button className="text-button" onClick={()=>setPattern([])}>다시 그리기</button><p className="muted">이 패턴은 저장되지 않습니다.</p></div>
      footer = <>{primary('패턴 등록 체험 완료',()=>{setPattern([]);next()},pattern.length<4)}<button className="footer-link" onClick={()=>{setPattern([]);next()}}>나중에 등록하기</button></>
      break
    case 'biometric':
      body = <>{heading(<>더 빠르고 간편하게<br/>Face로 인증하세요</>, '생체인증으로 편리하게 이용할 수 있어요.')}<Illustration kind="face"/>{notice('생체정보는 수집하지 않습니다. 등록 버튼을 누르면 체험 안내가 표시됩니다.')}</>
      footer = <div className="button-pair"><button className="secondary" onClick={next}>다음에</button>{primary('생체인증 등록',()=>setModal('생체인증 체험'))}</div>
      break
    case 'customer':
      body = <>{heading(<>고객님의 정보가 맞는지<br/>확인해 주세요</>)}{sample(()=>{setEnglish('KIM WOORI');setEmail('woori@example.com');setAddress('서울특별시 중구 세종대로 00');setDetail('가상빌딩 101호')})}<Field label="영문 이름"><input value={english} onChange={e=>setEnglish(e.target.value.toUpperCase())} placeholder="예: KIM WOORI" maxLength={60}/></Field><Field label="휴대폰 번호"><input value={phone || '01000000000'} readOnly className="read-only"/></Field><Field label="자택주소"><input value={address} onChange={e=>setAddress(e.target.value)} placeholder="가상 주소 입력" maxLength={120}/></Field><Field label="상세주소"><input value={detail} onChange={e=>setDetail(e.target.value)} placeholder="상세주소 입력 (선택)" maxLength={80}/></Field><Field label="이메일"><input type="email" value={email} onChange={e=>setEmail(e.target.value)} placeholder="example@email.com" maxLength={100}/></Field></>
      footer = primary('정보 확인하기',next,!/^[A-Z][A-Z\s-]+$/.test(english.trim()) || !address.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
      break
    case 'withdrawal':
      body = <>{heading(<>출금계좌로 등록할 계좌를<br/>확인해 주세요</>, '확인한 계좌를 출금계좌로 등록할게요.')}<label className="withdrawal-card"><span className="bank-symbol">W</span><span><strong>저축예금</strong><small>{bank} · •••• {account.slice(-4)||'0000'}</small></span><input type="checkbox" checked={!!checks.withdrawal} onChange={()=>toggle('withdrawal')}/><span className="round-check"><Icon name="check" size={17}/></span></label><p className="field-hint">등록할 계좌를 선택해 주세요.</p><div className="bottom-notice">{notice('계좌와 이체 한도는 화면 시연용입니다. 실제 계좌 등록이나 한도 변경은 이루어지지 않습니다.')}</div></>
      footer = primary('출금계좌 등록하기',next,!checks.withdrawal)
      break
    case 'complete':
      body = <div className="complete-page"><Illustration kind="success"/>{heading(<>우리WON뱅킹을 이용할<br/>준비가 끝났어요</>, `${name || '고객'}님, 새로운 금융생활을 시작해 보세요.`)}<div className="completion-summary"><div><span>본인확인</span><strong>체험 완료</strong></div><div><span>인증수단</span><strong>등록 과정 확인</strong></div><div><span>출금계좌</span><strong>{bank} · •••• {account.slice(-4)||'0000'}</strong></div></div><p className="muted">UI 체험이 완료되었습니다.<br/>실제 회원가입·인증서 발급은 이루어지지 않았어요.</p></div>
      footer = <>{primary('WON뱅킹 시작하기',()=>{setSignedIn(true);})}<button className="footer-link" onClick={reset}>처음부터 다시 보기</button></>
  }

  if (signedIn) return <BankingHome name={name} account={account} onLogout={()=>{setSignedIn(false);reset()}} />
  return <div className="studio"><header className="studio-header"><a href="#welcome" className="brand"><span className="brand-symbol"/><span>우리<span className="brand-won">WON</span>뱅킹</span></a><span className="prototype-badge"><span/> UI PROTOTYPE</span></header><div className="studio-layout"><aside className="presentation"><div className="eyebrow">WOORI WON BANKING</div><h2>우리의 첫 만남,<br/><span>금융이 더 쉬워지는 시작.</span></h2><p className="presentation-description">시작하기부터 이용 준비까지.<br/>우리WON뱅킹의 첫 이용 과정을 만나보세요.</p><nav className="flow-nav" aria-label="미리 볼 과정">{groupNames.map((label,i)=><button className={`${group===i?'current':''} ${group>i?'visited':''}`} key={label} onClick={()=>navigate(steps[groups[i]])}><span>{group>i?<Icon name="check" size={13}/>:String(i+1).padStart(2,'0')}</span>{label}{group===i&&<span className="viewing">미리 보는 중</span>}</button>)}</nav><div className="presentation-note"><span className="note-line"/><p>화면과 흐름을 확인하기 위한 비공식 UI 시안입니다.<br/>입력 정보는 서버에 전송하거나 저장하지 않습니다.</p></div></aside><div className="device-area"><div className={`device ${step==='welcome'?'is-welcome':''}`}><div className="device-status" aria-hidden="true"><span>9:41</span><div><span className="signal">▂▄▆█</span><svg width="17" height="13" viewBox="0 0 20 15"><path d="M1 4q9-7 18 0M4 7q6-5 12 0M7 10q3-3 6 0" fill="none" stroke="currentColor" strokeWidth="2"/><circle cx="10" cy="13" r="1.3"/></svg><span className="battery"/></div></div><header className="app-header">{step==='welcome'?<><span className="mini-brand">우리<span>WON</span>뱅킹</span><button className="icon-button" aria-label="화면 목록" onClick={()=>setModal('화면 목록')}><Icon name="menu"/></button></>:<><button className="icon-button" aria-label="이전 화면" onClick={()=>navigate(steps[Math.max(0,index-1)])}><Icon name="back"/></button><span>{titles[index]}</span><button className="icon-button" aria-label="시작 화면으로 나가기" onClick={()=>setModal('시작 화면으로 이동')}><Icon name="close" size={20}/></button></>}</header>{step!=='welcome'&&<div className="progress-track" role="progressbar" aria-label="이용 등록 진행률" aria-valuenow={index} aria-valuemin={0} aria-valuemax={16}><span style={{width:`${index/16*100}%`}}/></div>}{step !== 'welcome' && <div className="auto-preview-bar"><span>{autoPreview ? '과정 미리보기 · 화면마다 1초' : '입력 없이 등록 과정을 둘러보세요'}</span><button type="button" onClick={() => { setAutoPreview(value => !value); setError('') }}>{autoPreview ? '일시정지' : '자동으로 넘어가기'}</button></div>}<main ref={contentRef} className={`screen-content screen-${step}`}>{body}{error&&<p role="alert" className="error-message">{error}</p>}</main>{footer&&<footer className={`screen-footer ${step==='welcome'?'welcome-footer':''}`}>{footer}</footer>}<div className="home-indicator" aria-hidden="true"><span/></div></div><div className="device-caption"><span>WON BANKING</span><span>{String(index+1).padStart(2,'0')} / {steps.length} SCREENS</span></div></div></div><footer className="studio-footer"><span>우리 WON뱅킹 · 최초 이용 등록</span><span>DESIGN PREVIEW / 2026</span></footer>
    {modal&&<Modal title={modal} onClose={()=>setModal(null)}>{modal==='화면 목록'?<div className="screen-menu">{steps.map((s,i)=><button key={s} onClick={()=>{navigate(s);setModal(null)}}><span>{String(i+1).padStart(2,'0')}</span>{titles[i]}<Icon name="chevron" size={16}/></button>)}</div>:modal==='시작 화면으로 이동'?<><p className="modal-copy">진행 중인 체험을 종료할까요? 입력한 가상 정보가 초기화됩니다.</p><div className="button-pair"><button className="secondary" onClick={()=>setModal(null)}>계속 진행</button>{primary('종료하기',reset)}</div></>:modal==='생체인증 체험'?<><Illustration kind="face"/><p className="modal-copy">생체인증 등록 화면을 확인했어요. 실제 얼굴 정보는 수집하거나 등록하지 않습니다.</p>{primary('다음으로',()=>{setModal(null);next()})}</>:modal.endsWith('안내')?<><p className="modal-copy">{modal==='홈 화면 안내'?'최초 이용 등록 화면의 체험을 모두 마쳤어요. 등록 후 홈 화면은 다음 퍼블리싱 범위입니다.':modal==='로그인 안내'?'이번 시안은 처음 이용하는 고객의 시작하기 과정입니다. 기존 고객 로그인은 다음 퍼블리싱 범위입니다.':'이번 시안에서는 시작하기 이후의 이용 등록 과정을 확인할 수 있어요.'}</p>{primary('확인',()=>setModal(null))}</>:<><span className="terms-badge">퍼블리싱 확인용 예시</span><div className="terms-copy"><h3>{modal.replace('[필수] ','').replace('[선택] ','')}</h3><p>이 내용은 화면 구성과 동의 동작을 확인하기 위한 예시이며, 우리은행의 실제 약관이 아닙니다.</p><h4>1. 이용 목적</h4><p>본인확인, 인증수단 등록 및 금융서비스 이용 준비에 필요한 안내와 동의 화면을 확인합니다.</p><h4>2. 체험 정보 처리</h4><p>입력한 값은 현재 화면을 표시하는 동안만 사용합니다. 서버 전송, 브라우저 저장소 저장, 실제 인증은 수행하지 않습니다.</p><h4>3. 동의 여부</h4><p>필수 동의는 다음 화면 진행을 위한 UI 조건입니다. 선택 항목은 동의하지 않아도 계속할 수 있습니다.</p><h4>4. 실제 서비스 적용 전</h4><p>실제 제공 주체의 약관과 개인정보 처리방침으로 교체하고, 별도의 인증·계좌 연동 절차를 구현해야 합니다.</p></div>{primary('확인했어요',()=>setModal(null))}</>}</Modal>}
  </div>
}
