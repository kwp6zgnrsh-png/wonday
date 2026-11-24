// Approximate municipal centers for the regional forecast.
export const gyeonggiLocations = {
  가평군: [37.831, 127.510],
  고양시: [37.659, 126.833],
  과천시: [37.429, 126.987],
  광명시: [37.479, 126.865],
  광주시: [37.429, 127.255],
  구리시: [37.594, 127.130],
  군포시: [37.361, 126.935],
  김포시: [37.616, 126.715],
  남양주시: [37.637, 127.216],
  동두천시: [37.903, 127.061],
  부천시: [37.503, 126.766],
  성남시: [37.420, 127.126],
  수원시: [37.264, 127.029],
  시흥시: [37.380, 126.802],
  안산시: [37.322, 126.831],
  안성시: [37.008, 127.280],
  안양시: [37.394, 126.957],
  양주시: [37.785, 127.045],
  양평군: [37.492, 127.488],
  여주시: [37.298, 127.638],
  연천군: [38.096, 127.075],
  오산시: [37.150, 127.077],
  용인시: [37.241, 127.177],
  의왕시: [37.345, 126.969],
  의정부시: [37.739, 127.034],
  이천시: [37.272, 127.435],
  파주시: [37.760, 126.780],
  평택시: [36.992, 127.112],
  포천시: [37.895, 127.200],
  하남시: [37.540, 127.215],
  화성시: [37.200, 126.831],
} as const;

export type GyeonggiRegion = keyof typeof gyeonggiLocations;
export const gyeonggiRegions = Object.keys(gyeonggiLocations) as GyeonggiRegion[];
export const defaultWeatherRegion: GyeonggiRegion = "이천시";

export function isGyeonggiRegion(value: string): value is GyeonggiRegion {
  return Object.hasOwn(gyeonggiLocations, value);
}

export function getGyeonggiRegion(place: string) {
  const address = place.normalize("NFKC").trim()
    .replace(/^대한민국\s*/, "").replace(/^경기(?:도)?\s*/, "");
  return gyeonggiRegions.find((region) => address.startsWith(region));
}

export function isGyeonggiWorkplace(place: string) {
  return getGyeonggiRegion(place) !== undefined;
}
