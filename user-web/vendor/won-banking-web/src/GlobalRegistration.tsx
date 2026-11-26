import GlobalWelcomeIcon from './GlobalWelcomeIcon'
import { useEffect, useRef, useState } from 'react'
import GlobalHome from './GlobalHome'
import globalIcon from '../../../src/assets/won-global-icon.png'
import './GlobalRegistration.css'

const languages=[['您好','中文'],['Hello','English'],['こんにちは','日本語'],['Xin chào','Tiếng Việt'],['Сайн уу','Монгол'],['Halo','Indonesia'],['สวัสดีค่ะ','ไทย'],['Kamusta','Filipino'],['Здравствуйте','Русский'],['नमस्कार','नेपाली भाषा'],['Salom',"O‘zbek tili"],['안녕하세요','한국어']]
const moTerms=['MO Verification Terms of Service','Consent to Collection and Use of Personal Information for MO Verification']
const mobileTerms=['Consent to the Service Terms and Conditions','Consent to the Collection and Use of Personal information','Consent to the Processing of Unique Identification Information','Identification Service']
export default function GlobalRegistration(){
  const [step,setStep]=useState(0),[language,setLanguage]=useState('English'),[name,setName]=useState(''),[phone,setPhone]=useState(''),[birth,setBirth]=useState(''),[digit,setDigit]=useState(''),[mo,setMo]=useState<boolean[]>([false,false]),[mobile,setMobile]=useState<boolean[]>([false,false,false,false]),[terms,setTerms]=useState<null|'mo'|'mobile'>(null),[page,setPage]=useState(0),[provider,setProvider]=useState('NICE'),[message,setMessage]=useState(false),[signedIn,setSignedIn]=useState(false)
  const [autoPreview,setAutoPreview]=useState(false)
  const content=useRef<HTMLDivElement>(null),korean=language==='한국어'
  useEffect(()=>{
    if(!autoPreview||signedIn||terms||step<3||step>6)return
    const frames:Array<()=>void>=[]
    const type=(value:string,update:(value:string)=>void)=>{
      frames.push(()=>update(''))
      for(let i=1;i<=value.length;i++)frames.push(()=>update(value.slice(0,i)))
    }
    if(step===3&&!message){
      frames.push(()=>setMo([false,false]),()=>setMo([true,false]),()=>setMo([true,true]))
    }else if(step===4){
      type('Alex Kim',setName)
    }else if(step===5){
      type('01000000000',setPhone)
      type('950101',setBirth)
      type('5',setDigit)
    }else if(step===6){
      frames.push(()=>setMobile([false,false,false,false]))
      for(let i=0;i<4;i++)frames.push(()=>setMobile([0,1,2,3].map(j=>j<=i)))
    }
    const timers=frames.map((frame,i)=>window.setTimeout(frame,100+i/Math.max(1,frames.length)*650))
    const advance=window.setTimeout(()=>{
      if(step===3&&!message)setMessage(true)
      else if(message){setMessage(false);setStep(4)}
      else setStep(value=>value+1)
    },1000)
    return()=>{timers.forEach(window.clearTimeout);window.clearTimeout(advance)}
  },[autoPreview,step,message,terms,signedIn])
  useEffect(()=>{content.current?.scrollTo(0,0)},[step,terms,page,provider])
  useEffect(()=>{if(step!==7)return;const id=setTimeout(()=>setSignedIn(true),1000);return()=>clearTimeout(id)},[step])
  const next=()=>setStep(value=>value+1)
  const openTerms=(kind:'mo'|'mobile',index:number)=>{setTerms(kind);setPage(index);setProvider('NICE')}
  if(signedIn)return <GlobalHome name={name||'Global Customer'} onLogout={()=>{setAutoPreview(false);setSignedIn(false);setStep(2)}}/>
  const termList=terms==='mo'?moTerms:mobileTerms
  const checkList=(kind:'mo'|'mobile')=>{const items=kind==='mo'?moTerms:mobileTerms,values=kind==='mo'?mo:mobile,update=kind==='mo'?setMo:setMobile;return <div className="global-checks"><label className="global-agree-all"><input type="checkbox" checked={values.every(Boolean)} onChange={event=>update(items.map(()=>event.target.checked))}/><i className="global-check-mark" aria-hidden="true"/><span>{kind==='mo'?'[Required] Agree to All':'Agree to all'}</span></label>{items.map((title,i)=><div className="global-check-row" key={title}><label><input type="checkbox" checked={values[i]} onChange={event=>update(values.map((v,j)=>j===i?event.target.checked:v))}/><i className="global-check-mark" aria-hidden="true"/><span>{title}</span></label><button aria-label={`Read ${title}`} onClick={()=>openTerms(kind,i)}>›</button></div>)}</div>}
  let title=step===0?'앱 접근 권한 안내':step===1?'시작하기':step===2?(korean?'시작하기':'Start'):'Identity verification'
  if(terms)title='Agree to the Terms and Conditions'
  return <div className="global-registration" lang={step<2?'ko':korean?'ko':'en'}><header className="global-header">{step>1&&!terms&&<button className="global-back" aria-label="Back" onClick={()=>setStep(step-1)}>‹</button>}<span>{title}</span>{(terms||step>2)&&<button className="global-close" aria-label="Close" onClick={()=>terms?setTerms(null):setStep(2)}>×</button>}</header>
    {terms&&<><div className="global-term-caption"><span>[Required] {termList[page]}</span><b>{page+1} <em>/ {termList.length}</em></b></div>{terms==='mobile'&&<div className="global-provider">{['NICE','KCB'].map(value=><button key={value} className={provider===value?'active':''} onClick={()=>setProvider(value)}>{value}</button>)}</div>}</>}
    {!terms&&step>=2&&step<7&&<div className="global-auto-preview"><span>{autoPreview?'Auto preview · 1 second per screen':'Try registration with sample details'}</span><button type="button" aria-pressed={autoPreview} onClick={()=>{setAutoPreview(value=>!value);if(step===2)setStep(3)}}>{autoPreview?'Pause':'Auto-fill & continue'}</button></div>}<div className="global-content" ref={content}>
      {terms?<article className={`global-terms ${provider==='KCB'?'serif':''}`}><small>UI preview · Sample terms</small><h1>{terms==='mo'?(page===0?'MO인증 서비스 이용약관':'MO인증 개인정보 수집·이용 동의'):page===0?`Terms and Conditions of Identity Verification Service of ${provider}`:termList[page]}</h1><h2>{terms==='mo'?'제1조 (목적)':'Article 1 (Purpose)'}</h2><p>{terms==='mo'?'휴대전화 소지 확인 및 인증 절차의 화면 구성을 확인하기 위한 체험용 약관입니다. 실제 약관 동의나 문자 발송은 이루어지지 않습니다.':'These sample terms explain the identity verification preview. Information entered in this experience is not sent to a verification provider. No real identity verification is performed.'}</p><h2>{terms==='mo'?'제2조 (이용 안내)':'Article 2 (Definitions)'}</h2><p>{terms==='mo'?'체험 화면에서는 가상 정보만 사용해 주세요. 입력한 전화번호와 등록번호는 서버에 전송하거나 저장하지 않습니다.':'Use sample information to explore the registration screens. Your name, mobile number and registration details remain in this screen only and are not saved or transmitted.'}</p><div className="global-term-paging"><button disabled={page===0} onClick={()=>setPage(page-1)}>‹</button><button disabled={page===termList.length-1} onClick={()=>setPage(page+1)}>›</button></div></article>:<>
      {step===0&&<><h1>선택 권한</h1><div className="global-permissions">{[['camera','카메라','신분증 촬영, 서류제출 등에 사용'],['photo','사진','이체확인증 저장 등에 사용'],['location','위치','가까운 영업점 찾기 등에 사용']].map(([icon,label,desc])=><div key={icon}><svg viewBox="0 0 48 48" fill="none" stroke="#182027" strokeWidth="2.4">{icon==='camera'?<><path d="M6 14h8l4-6h12l4 6h8v26H6z"/><circle cx="24" cy="26" r="8" stroke="#39a8ff" strokeWidth="4"/></>:icon==='photo'?<><rect x="7" y="6" width="34" height="32" rx="4"/><path d="m10 33 10-11 7 7 5-5 7 9" fill="#39a8ff" stroke="none"/><circle cx="32" cy="15" r="3" fill="#8bd0ff" stroke="none"/></>:<><path d="M24 43S8 28 8 18a16 16 0 0 1 32 0c0 10-16 25-16 25Z"/><circle cx="24" cy="18" r="6" fill="#39a8ff" stroke="none"/></>}</svg><div><strong>{label}</strong><p>{desc}</p></div></div>)}</div></>}
      {step===1&&<><h1>언어를 선택해 주세요</h1><p className="global-subtitle">Please select a language</p><div className="global-languages">{languages.map(([hello,label])=><button key={label} onClick={()=>{setLanguage(label);next()}}><span>{hello}</span> {label}</button>)}</div></>}
      {step===2&&<><h1>{korean?'만나서 반가워요.':'Nice to meet you.'}</h1><p className="global-subtitle global-welcome-subtitle">{korean?'우리WON글로벌과 편리한 금융생활을 시작하세요.':'Start a convenient financial life with Woori WON Global.'}</p><div className="global-welcome-art" aria-hidden="true">{[['coin','bank','transfer','globe'],['globe','won','plane','coin']].map((icons,row)=><div className={`global-icon-row global-icon-row-${row}`} key={row}><div className="global-icon-track">{[0,1].map(copy=><div className="global-icon-set" key={copy}>{icons.map((icon,i)=><span key={i}><GlobalWelcomeIcon kind={icon}/></span>)}</div>)}</div></div>)}</div></>}
      {step===3&&<><h1>Please send the verification code entered to verify your identity.</h1><div className="global-message-art" aria-hidden="true"><div className="global-art-phone"><i/><small>Send verification message</small><div><img src={globalIcon} alt=""/><span>━━━━<br/>━━━</span></div></div><b><svg viewBox="0 0 32 32" fill="none" stroke="currentColor" strokeWidth="3.6" strokeLinecap="round" strokeLinejoin="round"><path d="M6 16h20M18 8l8 8-8 8"/></svg></b></div>{checkList('mo')}<p className="global-note">• Please note that depending on your mobile plan, you may be charged for sending a text message.</p></>}
      {step===4&&<><h1>Please enter your name.</h1><label className="global-field">Full name<input autoComplete="off" value={name} onChange={e=>setName(e.target.value)} placeholder="Input"/></label></>}
      {step===5&&<><h1>Please enter your Foreigner Registration Number or Resident Registration Number.</h1><label className="global-field">Mobile Phone Number<input type="tel" inputMode="tel" autoComplete="off" value={phone} onChange={e=>setPhone(e.target.value.replace(/\D/g,'').slice(0,11))} placeholder="01000000000"/></label><div className="global-field"><span>Alien registration number / Resident Registration Number</span><div className="global-registration-number"><input aria-label="6-digit date of birth" inputMode="numeric" autoComplete="off" maxLength={6} value={birth} onChange={e=>setBirth(e.target.value.replace(/\D/g,'').slice(0,6))} placeholder="6-digit date of birth"/><span>−</span><input aria-label="First digit after the hyphen" inputMode="numeric" type="password" maxLength={1} value={digit} onChange={e=>setDigit(e.target.value.replace(/\D/g,'').slice(0,1))}/><span className="global-mask">******</span></div></div><label className="global-field">Full name<input value={name} onChange={e=>setName(e.target.value)} autoComplete="off"/></label><small className="global-demo-note">Preview only. Use sample details; no information is submitted.</small></>}
      {step===6&&<><h1>Please agree to the required items for mobile identity verification.</h1>{checkList('mobile')}</>}
      {step===7&&<div className="global-complete"><span>✓</span><h1>Registration complete</h1><p>Welcome, {name}.<br/>Opening your home screen…</p></div>}
      </>}
    </div>
    <footer className="global-footer">{terms?<button className="global-primary" onClick={()=>{if(terms==='mo')setMo([true,true]);else setMobile([true,true,true,true]);setTerms(null)}}>Agree to all</button>:<>{step===0&&<><p>선택 권한은 필요한 시점에 허용을 요청하며, 허용하지 않으셔도 앱을 사용할 수 있습니다.</p><button className="global-primary" onClick={next}>확인</button></>}{step===2&&<><button className="global-primary" onClick={next}>{korean?'시작하기':'Start'}</button><div className="global-login"><span>{korean?'이미 가입하셨나요?':'You are already a member of Woori WON Global.'}</span><button onClick={()=>setSignedIn(true)}>Login ›</button></div>{!['English','한국어'].includes(language)&&<small className="global-demo-note">{language} selected · English preview</small>}</>}{step===3&&<button className="global-primary" disabled={!mo.every(Boolean)} onClick={()=>setMessage(true)}>Send message</button>}{step===4&&<button className="global-primary" disabled={!name.trim()} onClick={next}>Next</button>}{step===5&&<button className="global-primary" disabled={!/^01\d{8,9}$/.test(phone)||birth.length!==6||digit.length!==1||!name.trim()} onClick={next}>Next</button>}{step===6&&<button className="global-primary" disabled={!mobile.every(Boolean)} onClick={next}>Confirm</button>}</>}</footer>
    {message&&<div className="global-message-backdrop"><section className="global-message-sheet" role="dialog" aria-modal="true" aria-label="Verification message preview"><header><strong>New Message</strong><button aria-label="Close message" onClick={()=>setMessage(false)}>×</button></header><p className="global-message-to">To: <span>Woori Bank · Preview</span></p><div className="global-sms-bubble">[Woori Bank] Verification message<br/>WON GLOBAL DEMO</div><p className="global-demo-note">Message preview only. No SMS will be sent.</p><button className="global-primary" onClick={()=>{setMessage(false);next()}}>Send · Continue</button></section></div>}
  </div>
}
