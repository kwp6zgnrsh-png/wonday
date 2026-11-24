import { useId, useRef, useState } from "react";
import FarmIcon from "./FarmIcon";
import { getWorkplaceMap } from "./farmMapLocation";
import type { WorkplacePin } from "./farmMapLocation";

export default function FarmWorkplaceMap({ address, registeredPin }: { address: string; registeredPin?: WorkplacePin }) {
  const mapId = useId();
  const dialog = useRef<HTMLDialogElement>(null);
  const [isExpanded, setIsExpanded] = useState(false);
  const place = address.trim();
  const map = getWorkplaceMap(place, registeredPin);
  const pinNote = map?.pin ? "작업장에 등록된 핀" : map ? `${map.label} 중심 핀 · 상세 위치는 카카오맵에서 확인` : "";
  const mapUrl = map?.pin
    ? `https://map.kakao.com/link/map/${encodeURIComponent("작업장")},${map.pin.latitude},${map.pin.longitude}`
    : "https://map.kakao.com/link/search/" + encodeURIComponent(place);
  const kakaoLink = place && (
    <a className="farm-map-kakao" href={mapUrl} target="_blank" rel="noreferrer">
      {map?.pin ? "카카오맵에서 위치 보기" : "카카오맵에서 검색"} <FarmIcon name="arrow" />
    </a>
  );

  return (
    <section className="farm-workplace" aria-labelledby={`${mapId}-title`}>
      <h3 id={`${mapId}-title`}>작업장 위치</h3>
      <figure className="farm-map-card">
        {map && (
          <div className="farm-map-preview">
            <iframe className="farm-map-frame" src={map.previewUrl} title={`${map.label} 주변 지도 미리보기`}
              loading="lazy" tabIndex={-1} inert />
            <button className="farm-map-expand" type="button" aria-haspopup="dialog" onClick={() => {
              dialog.current?.showModal();
              setIsExpanded(true);
            }}>
              <FarmIcon name="expand" />지도 크게 보기
            </button>
          </div>
        )}
        <figcaption className="farm-map-caption">
          <div className="farm-map-address"><FarmIcon name="pin" />{place || "등록된 주소가 없어요."}</div>
          {map && <p className="farm-map-scope">{pinNote}</p>}
          {kakaoLink}
        </figcaption>
      </figure>
      {map && (
        <dialog className="farm-map-dialog" ref={dialog} aria-labelledby={`${mapId}-detail-title`}
          onClose={() => setIsExpanded(false)}>
          <div className="farm-map-detail">
            <header className="farm-map-detail-header">
              <h3 id={`${mapId}-detail-title`}>작업장 주변 지도</h3>
              <button className="farm-map-close" type="button" aria-label="지도 닫기"
                onClick={() => dialog.current?.close()}><FarmIcon name="close" /></button>
            </header>
            {isExpanded && <iframe className="farm-map-frame" src={map.detailUrl} title={`${map.label} 확대 지도`} />}
            <div className="farm-map-caption">
              <div className="farm-map-address"><FarmIcon name="pin" />{place}</div>
              <p className="farm-map-scope">{pinNote}</p>
              {kakaoLink}
            </div>
          </div>
        </dialog>
      )}
    </section>
  );
}
