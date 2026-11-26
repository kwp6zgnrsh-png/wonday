import phoneIcon from "./assets/apple-phone.png";
import messagesIcon from "./assets/apple-messages.png";
import safariIcon from "./assets/apple-safari.png";
import musicIcon from "./assets/apple-music.png";
import { useState } from "react";
export default function IphoneDock() {
  const [selected, setSelected] = useState("");
  return <><div className="home-dock iphone-native-dock">
    <button type="button" className="ios-phone" aria-label="전화" onClick={() => setSelected("전화")}><img src={phoneIcon} alt="" draggable={false} /></button>
    <button type="button" className="ios-safari" aria-label="Safari" onClick={() => setSelected("Safari")}><img src={safariIcon} alt="" draggable={false} /></button>
    <button type="button" className="ios-messages" aria-label="메시지" onClick={() => setSelected("메시지")}><img src={messagesIcon} alt="" draggable={false} /></button>
    <button type="button" className="ios-music" aria-label="음악" onClick={() => setSelected("음악")}><img src={musicIcon} alt="" draggable={false} /></button>
  </div>{selected && <div className="ios-dock-dialog-shade" onClick={() => setSelected("")}><section role="dialog" aria-modal="true" aria-label={selected} onClick={event => event.stopPropagation()}><h3>{selected}</h3><p>아이폰 기본 앱 아이콘 미리보기예요.</p><button type="button" onClick={() => setSelected("")}>확인</button></section></div>}</>;
}