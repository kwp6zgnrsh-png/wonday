import { useEffect, useState } from "react";
import { defaultWeatherRegion, getGyeonggiRegion, gyeonggiLocations, gyeonggiRegions, isGyeonggiRegion } from "./gyeonggiRegions";
import type { GyeonggiRegion } from "./gyeonggiRegions";
import InsuranceIcon from "./InsuranceIcon";

type DailyForecast = {
  weather_code: Array<number | null>;
  temperature_2m_max: Array<number | null>;
  precipitation_probability_max: Array<number | null>;
  wind_gusts_10m_max: Array<number | null>;
};

type Forecast = { code: number; high: number; rainChance: number | null; gust: number | null };
type Result = { state: "loading" | "unavailable" } | { state: "ready"; forecast: Forecast };

function weatherMessage({ code, high, rainChance, gust }: Forecast) {
  if (code >= 95) return { icon: "rain", title: "천둥·번개 소식이 있어요", tip: "외출 전 최신 예보를 확인해 주세요." } as const;
  if ([71, 73, 75, 77, 85, 86].includes(code))
    return { icon: "snow", title: "눈 소식이 있어요", tip: "이동할 때 미끄러운 길을 살펴보세요." } as const;
  if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || (rainChance ?? 0) >= 60)
    return { icon: "rain", title: "비 소식이 있어요", tip: "외출할 때 우산을 챙겨 주세요." } as const;
  if ((gust ?? 0) >= 45) return { icon: "wind", title: "바람이 강할 수 있어요", tip: "작업 전에 현장 날씨를 한 번 더 확인해 주세요." } as const;
  if (high >= 31) return { icon: "sun", title: "오늘은 더워요", tip: "야외 작업을 나갈 때 물을 챙겨 주세요." } as const;
  if (high <= 5) return { icon: code <= 1 ? "sun" : "cloud", title: "오늘은 쌀쌀해요", tip: "외출할 때 따뜻한 겉옷을 챙겨 주세요." } as const;
  if ([45, 48].includes(code)) return { icon: "cloud", title: "안개가 낄 수 있어요", tip: "이동하기 전에 시야를 확인해 주세요." } as const;
  return code <= 1
    ? { icon: "sun", title: "오늘은 대체로 맑아요", tip: "한낮에는 햇볕을 가릴 모자를 챙겨 보세요." } as const
    : { icon: "cloud", title: "오늘은 구름이 많아요", tip: "일정에 맞춰 최신 예보를 확인해 주세요." } as const;
}

function WeatherForecast({ region }: { region: GyeonggiRegion }) {
  const [result, setResult] = useState<Result>({ state: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const [latitude, longitude] = gyeonggiLocations[region];
    const query = new URLSearchParams({
      latitude: String(latitude),
      longitude: String(longitude),
      daily: "weather_code,temperature_2m_max,precipitation_probability_max,wind_gusts_10m_max",
      timezone: "Asia/Seoul",
      forecast_days: "1",
    });
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 7000);
    let active = true;

    fetch("https://api.open-meteo.com/v1/forecast?" + query, { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error("Forecast unavailable");
        const { daily } = await response.json() as { daily?: DailyForecast };
        const code = daily?.weather_code?.[0];
        const high = daily?.temperature_2m_max?.[0];
        if (typeof code !== "number" || typeof high !== "number") throw new Error("Forecast incomplete");
        if (active) setResult({
          state: "ready",
          forecast: {
            code, high,
            rainChance: daily?.precipitation_probability_max?.[0] ?? null,
            gust: daily?.wind_gusts_10m_max?.[0] ?? null,
          },
        });
      })
      .catch(() => { if (active) setResult({ state: "unavailable" }); })
      .finally(() => window.clearTimeout(timeout));

    return () => {
      active = false;
      controller.abort();
      window.clearTimeout(timeout);
    };
  }, [region, attempt]);

  if (result.state !== "ready") {
    return <div className="weather-status" role="status">
      <p>{result.state === "loading" ? "오늘 예보를 확인하고 있어요." : "오늘 예보를 불러오지 못했어요."}</p>
      {result.state === "unavailable" && <button className="weather-retry" onClick={() => {
        setResult({ state: "loading" });
        setAttempt(attempt + 1);
      }}>다시 확인</button>}
    </div>;
  }

  const outlook = weatherMessage(result.forecast);
  const { high, rainChance } = result.forecast;
  return <>
    <div className="weather-report" role="status">
      <span className="weather-report-icon"><InsuranceIcon name={outlook.icon} /></span>
      <div>
        <strong>{outlook.title}</strong>
        <p>{outlook.tip}</p>
      </div>
    </div>
    <div className="weather-details">
      <span>최고 {Math.round(high)}°C{rainChance !== null && ` · 강수 확률 최대 ${rainChance}%`}</span>
      <a href="https://open-meteo.com/" target="_blank" rel="noreferrer">Open-Meteo</a>
    </div>
  </>;
}

export default function TodayWeather({ region, onRegionChange }: {
  region: GyeonggiRegion;
  onRegionChange: (region: GyeonggiRegion) => void;
}) {
  const currentRegion = isGyeonggiRegion(region) ? region : defaultWeatherRegion;
  return (
    <section className="today-weather" aria-label="오늘의 날씨 안내">
      <div className="weather-heading">
        <h3>오늘의 날씨</h3>
        <select aria-label="경기도 날씨 지역" value={currentRegion}
          onChange={(event) => {
            const city = event.target.value;
            if (isGyeonggiRegion(city)) onRegionChange(city);
          }}>
          {gyeonggiRegions.map((city) => <option key={city}>{city}</option>)}
        </select>
      </div>
      <WeatherForecast key={currentRegion} region={currentRegion} />
    </section>
  );
}

export function WorkplaceWeather({ address }: { address: string }) {
  const region = getGyeonggiRegion(address);
  return (
    <section className="today-weather farm-workplace-weather" aria-label="작업지 오늘 날씨">
      <div className="weather-heading">
        <h3>작업지 오늘 날씨</h3>
        {region && <span className="farm-weather-location">경기도 {region}</span>}
      </div>
      {region ? <>
        <WeatherForecast key={region} region={region} />
        <p className="farm-weather-note">작업지가 속한 시·군 중심 기준의 오늘 예보예요.</p>
      </> : <p className="farm-weather-note">작업지의 경기도 시·군 정보가 없어 날씨를 확인할 수 없어요.</p>}
    </section>
  );
}
