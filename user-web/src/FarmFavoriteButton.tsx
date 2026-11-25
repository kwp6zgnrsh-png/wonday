export default function FarmFavoriteButton({ title, selected, onClick }: {
  title: string;
  selected: boolean;
  onClick: () => void;
}) {
  return <button type="button" className="farm-list-favorite" aria-label={`${title} 찜${selected ? " 해제" : "하기"}`}
    aria-pressed={selected} onClick={onClick}>
    <svg viewBox="0 0 24 24" fill={selected ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.7"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z" />
    </svg>
    <span>{selected ? "찜 해제" : "찜하기"}</span>
  </button>;
}