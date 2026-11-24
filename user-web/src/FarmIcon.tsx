const shapes = {
  clock: "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM12 7v5l3 2",
  cup: "M4 8h12v7a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5Zm12 1h2a3 3 0 0 1 0 6h-2M7 3v2m4-2v2M3 22h15",
  users: "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2m13-17a4 4 0 0 1 0 8m7 9v-2a4 4 0 0 0-3-3.87M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z",
  expand: "M9 4H4v5m11-5h5v5M4 15v5h5m11-5v5h-5",
  close: "m6 6 12 12M18 6 6 18",
  bookmark: "M7 4h10a2 2 0 0 1 2 2v15l-7-4-7 4V6a2 2 0 0 1 2-2Z",
  check: "m5 12 4 4L19 6",
  sprout: "M12 21v-8M12 15C5 15 3 11 3 6c5 0 9 2 9 7m0 0c0-6 4-9 9-9 0 6-3 9-9 9M7 21h10",
  profile: "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2m11-14a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z",
  clipboard: "M9 5H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-3M9 3h6v4H9zm0 10h6m-6 4h4",
  calendarOff: "M7 3v4m10-4v4M4 10h16M6 5h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Zm4 9 4 4m0-4-4 4",
  logout: "M10 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h5m3-12 5 4-5 4m5-4H9",
  search: "M16 16l4 4M18 11a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z",
  pin: "M19 10c0 5-7 10-7 10s-7-5-7-10a7 7 0 0 1 14 0Zm-5 0a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z",
  calendar: "M7 3v4m10-4v4M4 10h16M6 5h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Zm2 9h2m4 0h2m-8 3h2",
  shield: "M12 3 4 6v5c0 5 3.5 8.5 8 10 4.5-1.5 8-5 8-10V6Zm-3 9 2 2 4-4",
  arrow: "M4 12h15m-6-6 6 6-6 6",
  back: "M20 12H5m6 6-6-6 6-6",
  grid: "M4 4h6v6H4zm10 0h6v6h-6zM4 14h6v6H4zm10 0h6v6h-6z",
  field: "M3 15c3-3 6-3 9 0s6 3 9 0M3 20c3-3 6-3 9 0s6 3 9 0M8 8a3 3 0 1 0 0 .01",
  greenhouse: "M3 20v-7a9 9 0 0 1 18 0v7ZM8 20v-6c0-5 2-10 4-10s4 5 4 10v6m-4-5v5",
  spray: "M9 12h6a3 3 0 0 1 3 3v6H6v-6a3 3 0 0 1 3-3Zm1 0V8h5l3-3h-8L8 7M19 8l2 1m-2-5 2-1",
  plus: "M12 5v14M5 12h14",
};

export type FarmIconName = keyof typeof shapes;

export default function FarmIcon({ name }: { name: FarmIconName }) {
  return (
    <svg className="farm-icon-line" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={shapes[name]} />
    </svg>
  );
}
