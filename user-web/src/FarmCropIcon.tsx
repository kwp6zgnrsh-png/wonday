const silhouettes = {
  apple: <>
    <path d="M32 23c-7-6-19-5-24 4-7 12 3 30 15 30 4 0 6-2 9-2s5 2 9 2c12 0 22-18 15-30-5-9-17-10-24-4Z" fill="var(--crop-body)" />
    <path d="M30 20C29 11 37 6 47 7c-2 8-8 13-17 13Z" fill="var(--crop-leaf)" />
  </>,
  berry: <>
    <path d="M32 17c15-7 25 1 25 12 0 12-16 28-25 28S7 41 7 29c0-11 10-19 25-12Z" fill="var(--crop-body)" />
    <path d="M32 15c-6-8-13-9-18-6 2 6 6 9 11 10-2 2-3 4-3 6 5 0 8-2 10-5 2 3 5 5 10 5 0-2-1-4-3-6 5-1 9-4 11-10-5-3-12-2-18 6Z" fill="var(--crop-leaf)" />
    <path d="M20 32h3v5h-3zm21 0h3v5h-3zM31 41h3v5h-3z" fill="var(--crop-seed)" />
  </>,
  cabbage: <>
    <path d="M32 57C16 57 6 48 6 34c0-8 6-14 13-13 1-9 6-14 13-14s12 5 13 14c7-1 13 5 13 13 0 14-10 23-26 23Z" fill="var(--crop-body)" />
    <path d="M32 53C21 46 15 35 17 27c7-1 12 4 15 9 3-5 8-10 15-9 2 8-4 19-15 26Z" fill="var(--crop-leaf)" />
  </>,
  tomato: <>
    <path d="M32 17c16 0 26 8 26 20S47 57 32 57 6 49 6 37s10-20 26-20Z" fill="var(--crop-body)" />
    <path d="M31 7h4l1 9 12-3-6 8 5 6-12-3-3 7-4-7-12 3 6-7-5-7 12 3Z" fill="var(--crop-leaf)" />
  </>,
  carrot: <>
    <path d="M26 20c6 0 18 11 18 18 0 5-24 16-33 19-4 1-6-2-5-5 3-9 14-32 20-32Z" fill="var(--crop-body)" />
    <path d="M36 21c0-8 2-13 8-15 3-1 6 1 5 4l-3 7 7-3c4-1 6 2 5 5-2 6-8 8-15 8Z" fill="var(--crop-leaf)" />
  </>,
  radish: <>
    <path d="M21 26c4-5 18-6 23 0 5 7-1 22-8 27-4 3-7 3-10 6-1-3 1-6 0-10-3-7-9-16-5-23Z" fill="var(--crop-body)" />
    <path d="M31 25C21 23 17 16 18 8c7 0 12 4 14 10 2-8 7-12 15-13 1 9-4 17-13 20Z" fill="var(--crop-leaf)" />
  </>,
  scallion: <>
    <path d="M15 58c-4-2-5-6-3-9l14-20 11 6-12 21c-2 3-6 4-10 2Z" fill="var(--crop-body)" />
    <path d="M25 31 33 8c1-4 7-4 8 0l-2 16L50 9c3-3 8 0 6 4L37 36Z" fill="var(--crop-leaf)" />
  </>,
  spinach: <>
    <path d="M30 52C14 51 7 41 9 25c4-1 8 1 11 3-2-10 2-17 10-22 6 3 9 10 9 17 4-4 9-6 15-5 2 12-5 25-19 33l-4 7h-5Z" fill="var(--crop-body)" />
    <path d="M28 50c0-10 1-19 6-29 1 9 0 19-3 28Z" fill="var(--crop-leaf)" />
  </>,
  lettuce: <>
    <path d="M31 57C15 56 5 48 5 36c0-6 4-10 9-12C13 17 17 11 24 12c3-6 11-8 16-1 7-2 12 3 12 10 6 3 8 8 7 15-2 13-12 21-28 21Z" fill="var(--crop-body)" />
    <path d="M15 35C19 28 25 29 31 37c5-8 11-10 18-5-2 10-9 17-18 20-8-4-14-10-16-17Z" fill="var(--crop-leaf)" />
  </>,
  cucumber: <>
    <path d="M11 40 38 11c5-5 13-5 18 0s5 12 0 18L29 55c-6 5-13 5-18 0s-5-10 0-15Z" fill="var(--crop-body)" />
    <path d="M18 44 43 17c2-2 5 0 4 2L22 47c-2 2-5 0-4-3Z" fill="var(--crop-leaf)" />
  </>,
  sprout: <>
    <path d="M31 43C15 43 7 33 8 19c13-1 22 5 25 16v19h-4Z" fill="var(--crop-body)" />
    <path d="M31 40c0-18 10-29 25-30 1 15-8 27-25 30Z" fill="var(--crop-leaf)" />
  </>,
} as const;

const crops: Record<string, keyof typeof silhouettes> = {
  사과: "apple",
  딸기: "berry",
  배추: "cabbage",
  토마토: "tomato",
  당근: "carrot",
  무: "radish",
  대파: "scallion",
  쪽파: "scallion",
  시금치: "spinach",
  상추: "lettuce",
  오이: "cucumber",
};

export default function FarmCropIcon({ crop }: { crop: string }) {
  const name = crop.trim();
  const icon = Object.hasOwn(crops, name) ? crops[name] : "sprout";
  return (
    <span className={`farm-crop farm-crop--${icon}`} aria-hidden="true">
      <svg viewBox="0 0 64 64" focusable="false">{silhouettes[icon]}</svg>
    </span>
  );
}
