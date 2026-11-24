const paths = {
  products: "M5 3.5h12a2 2 0 0 1 2 2V20H6a2 2 0 0 1-2-2V5.5a2 2 0 0 1 2-2ZM4 17h15M8 7h7m-7 4h5",
  claim: "M13 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10Zm0 0v7h7m-12 5 2.5 2.5L16 12",
  home: "m3 10 9-7 9 7v9a2 2 0 0 1-2 2h-4v-6H9v6H5a2 2 0 0 1-2-2Z",
  payment: "M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Zm0 3h18m-14 5h4",
  profile: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z",
  bell: "M18 8a6 6 0 0 0-12 0c0 6-3 7-3 9h18c0-2-3-3-3-9Zm-8 12a2 2 0 0 0 4 0",
  launcher: "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z",
  sun: "M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM12 2v2m0 16v2m10-10h-2M4 12H2m17.1-7.1-1.4 1.4M6.3 17.7l-1.4 1.4m14.2 0-1.4-1.4M6.3 6.3 4.9 4.9",
  cloud: "M7 17a4 4 0 1 1 .8-7.9A5.5 5.5 0 0 1 18.3 11a3 3 0 0 1-.3 6Z",
  rain: "M7 15a4 4 0 1 1 .8-7.9A5.5 5.5 0 0 1 18.3 9a3 3 0 0 1-.3 6Zm2 3-1 3m5-3-1 3m5-3-1 3",
  snow: "M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9M9 3l3 3 3-3M9 21l3-3 3 3M3 10l4 1-1-4m12 10-1-4 4 1M3 14l4-1-1 4m12-10-1 4 4-1",
  wind: "M3 8h13a2 2 0 1 0-2-2M3 12h16a2 2 0 1 1-2 2M3 16h8a2 2 0 1 1-2 2",
};

export type InsuranceIconName = keyof typeof paths;

export default function InsuranceIcon({ name }: { name: InsuranceIconName }) {
  return (
    <svg className="insurance-icon-line" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={paths[name]} />
    </svg>
  );
}
