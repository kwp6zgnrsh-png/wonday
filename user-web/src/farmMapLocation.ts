import { getGyeonggiRegion, gyeonggiLocations } from "./gyeonggiRegions";

export type WorkplacePin = { latitude: number; longitude: number };

const demoTownships = {
  "이천시 장호원읍": [37.1318, 127.597],
  "평택시 진위면": [37.10531, 127.08763],
  "양평군 양서면": [37.53847, 127.378],
} as const;

function isGyeonggiPin({ latitude, longitude }: WorkplacePin) {
  return latitude >= 36.8 && latitude <= 38.4 && longitude >= 126.25 && longitude <= 128;
}

export function parseWorkplacePin(value: string): WorkplacePin | undefined {
  const parts = value.split(",").map((part) => part.trim());
  if (parts.length !== 2 || parts.some((part) => !/^-?\d+(?:\.\d+)?$/.test(part))) return undefined;
  const [latitude, longitude] = parts.map(Number);
  const pin = { latitude, longitude };
  return isGyeonggiPin(pin) ? pin : undefined;
}

export function getWorkplaceMap(address: string, registeredPin?: WorkplacePin) {
  const region = getGyeonggiRegion(address);
  if (!region) return undefined;
  const [, township] = address.trim().replace(/^경기(?:도)?\s+/, "").split(/\s+/, 2);
  const townshipKey = `${region} ${township}`;
  const localCenter = Object.hasOwn(demoTownships, townshipKey)
    ? demoTownships[townshipKey as keyof typeof demoTownships]
    : undefined;
  const pin = registeredPin && isGyeonggiPin(registeredPin) ? registeredPin : undefined;
  const [lat, lon] = pin ? [pin.latitude, pin.longitude] : localCenter ?? gyeonggiLocations[region];
  return {
    label: localCenter ? township : region,
    pin,
    previewUrl: mapEmbedUrl(lat, lon, pin ? 0.008 : localCenter ? 0.034 : 0.065),
    detailUrl: mapEmbedUrl(lat, lon, pin ? 0.0025 : localCenter ? 0.009 : 0.015),
  };
}

function mapEmbedUrl(lat: number, lon: number, radius: number) {
  const latitudeRadius = radius * 0.63;
  const bbox = [lon - radius, lat - latitudeRadius, lon + radius, lat + latitudeRadius]
    .map((coordinate) => coordinate.toFixed(5)).join(",");
  return "https://www.openstreetmap.org/export/embed.html?" + new URLSearchParams({
    bbox,
    layer: "mapnik",
    marker: `${lat},${lon}`,
  });
}
