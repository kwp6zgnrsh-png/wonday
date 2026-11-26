import { useEffect, useState } from "react";
import { defaultWeatherRegion, gyeonggiLocations } from "./gyeonggiRegions";
export default function IphoneHomeWidgets({ onWeather, onCalendar }: { onWeather: () => void; onCalendar: () => void }) {
  const [weather, setWeather] = useState<{ temperature: number; high: number; low: number; code: number } | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 8000);
    let active = true;
    const [latitude, longitude] = gyeonggiLocations[defaultWeatherRegion];
    const query = new URLSearchParams({ latitude: String(latitude), longitude: String(longitude), current: "temperature_2m,weather_code", daily: "temperature_2m_max,temperature_2m_min", timezone: "Asia/Seoul", forecast_days: "1" });
    fetch("https://api.open-meteo.com/v1/forecast?" + query, { signal: controller.signal }).then(async response => {
      if (!response.ok) throw new Error("weather unavailable");
      const data = await response.json();
      const values = [data.current?.temperature_2m, data.daily?.temperature_2m_max?.[0], data.daily?.temperature_2m_min?.[0], data.current?.weather_code];
      if (!values.every(value => typeof value === "number" && Number.isFinite(value))) throw new Error("incomplete weather");
      if (active) setWeather({ temperature: values[0], high: values[1], low: values[2], code: values[3] });
    }).catch(() => { if (active) setFailed(true); }).finally(() => window.clearTimeout(timeout));
    return () => { active = false; controller.abort(); window.clearTimeout(timeout); };
  }, []);
  const condition = !weather ? "" : weather.code >= 95 ? "뇌우" : [71,73,75,77,85,86].includes(weather.code) ? "눈" : weather.code >= 51 ? "비" : weather.code >= 45 ? "안개" : weather.code <= 1 ? "맑음" : "흐림";
  const icon = condition === "맑음" ? "☀" : condition === "비" ? "🌧" : condition === "눈" ? "❄" : condition === "뇌우" ? "⛈" : "☁";
  const now = new Date();
  const parts = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "long", day: "numeric", weekday: "short" }).format(now);
  const date = new Intl.DateTimeFormat("en-US", { timeZone: "Asia/Seoul", day: "numeric" }).format(now);
  const weekday = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", weekday: "long" }).format(now);
  return <div className="iphone-widgets">
    <div><button type="button" className="iphone-weather-widget" onClick={onWeather} aria-label="날씨 확인">
      <strong>경기 {defaultWeatherRegion}</strong><span className="iphone-weather-temp">{weather ? Math.round(weather.temperature) : "—"}°</span><span className="iphone-weather-cloud" aria-hidden="true">{icon}</span><span>{weather ? condition : failed ? "날씨를 불러오지 못했어요" : "날씨 확인 중"}</span><small>{weather ? `최고:${Math.round(weather.high)}° 최저:${Math.round(weather.low)}°` : "눌러서 예보 보기"}</small>
    </button><a className="iphone-weather-source" href="https://open-meteo.com/" target="_blank" rel="noreferrer">Open-Meteo · 이천시 중심</a></div>
    <div><button type="button" className="iphone-calendar-widget" onClick={onCalendar} aria-label="캘린더 열기">
      <span className="iphone-calendar-weekday">{weekday}</span><strong>{date}</strong><span className="iphone-calendar-event"><i />농작업 일정 확인</span><small>{parts}</small>
    </button><span className="iphone-widget-label">캘린더</span></div>
  </div>;
}