import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";

type Props<T extends { id: string | number }> = {
  items: T[];
  queryKey: string;
  pageSize?: number;
  renderItem: (item: T) => ReactNode;
};

export default function FarmCursorList<T extends { id: string | number }>(props: Props<T>) {
  return <CursorSession key={JSON.stringify([props.queryKey, props.items.map(item => item.id)])} {...props} />;
}

function CursorSession<T extends { id: string | number }>({ items, pageSize = 8, renderItem }: Props<T>) {
  // The last rendered record is the cursor; each batch continues after its ID.
  const [cursor, setCursor] = useState<string | number | null>(null);
  const marker = useRef<HTMLDivElement>(null);
  const cursorIndex = cursor === null ? -1 : items.findIndex(item => item.id === cursor);
  const visible = items.slice(0, cursorIndex + 1 + pageSize);
  const hasMore = visible.length < items.length;
  const nextCursor = visible.at(-1)?.id ?? null;
  const loadMore = useCallback(() => {
    if (hasMore) setCursor(nextCursor);
  }, [hasMore, nextCursor]);

  useEffect(() => {
    const target = marker.current;
    if (!target || !hasMore || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) loadMore();
    }, { root: target.closest(".farm-screen"), rootMargin: "0px 0px 160px 0px" });
    observer.observe(target);
    return () => observer.disconnect();
  }, [hasMore, loadMore]);

  return <>
    {visible.map(renderItem)}
    {items.length > 0 && <div ref={marker} className="farm-cursor-marker">
      {hasMore ? <button type="button" onClick={loadMore}>다음 내역 불러오기 <span>{visible.length} / {items.length}</span></button>
        : <p role="status">모든 내역을 확인했어요.</p>}
    </div>}
  </>;
}
