import FarmIcon from "./FarmIcon";
import { workDateRangeError, workPeriods } from "./farmWorkPeriod";
import type { WorkDateRange, WorkPeriod, WorkPeriodDirection } from "./farmWorkPeriod";

export default function FarmWorkPeriodFilter({ period, range, direction, onDirectionChange, onPeriodChange, onRangeChange }: {
  period: WorkPeriod;
  range: WorkDateRange;
  direction: WorkPeriodDirection;
  onDirectionChange: (direction: WorkPeriodDirection) => void;
  onPeriodChange: (period: WorkPeriod) => void;
  onRangeChange: (range: WorkDateRange) => void;
}) {
  const error = workDateRangeError(range);
  return (
    <section className="farm-work-period" aria-label="내 근무 조회 기간">
      <div className="farm-work-period-heading"><h3>조회 기간</h3><span>근무일 기준</span></div>
      <div className="farm-work-direction" role="group" aria-label="근무 조회 구분">
        <button type="button" aria-pressed={direction === "past"} onClick={() => { onDirectionChange("past"); if (period === "직접 설정") onPeriodChange("30일"); }}>지난 근무</button>
        <button type="button" aria-pressed={direction === "upcoming"} onClick={() => { onDirectionChange("upcoming"); if (period === "직접 설정") onPeriodChange("30일"); }}>예정 근무</button>
      </div>
      <div className="farm-work-period-buttons" role="group" aria-label="근무 기간 선택">
        {workPeriods.map((value) => <button key={value} type="button" aria-pressed={period === value} onClick={() => onPeriodChange(value)}>{value}</button>)}
      </div>
      {period === "직접 설정" ? <div className="form-grid farm-work-custom-range">
        <label>조회 시작일<input type="date" value={range.from} max={range.to || undefined}
          aria-invalid={!!error} aria-describedby={error ? "farm-work-period-error" : undefined}
          onInput={(event) => onRangeChange({ ...range, from: event.currentTarget.value })}
          onChange={(event) => onRangeChange({ ...range, from: event.currentTarget.value })} /></label>
        <label>조회 종료일<input type="date" value={range.to} min={range.from || undefined}
          aria-invalid={!!error} aria-describedby={error ? "farm-work-period-error" : undefined}
          onInput={(event) => onRangeChange({ ...range, to: event.currentTarget.value })}
          onChange={(event) => onRangeChange({ ...range, to: event.currentTarget.value })} /></label>
      </div> : <p className="farm-work-period-hint">{direction === "upcoming" ? "오늘부터" : "오늘까지 최근"} {period}간의 근무를 보여드려요.</p>}
      {error ? <p id="farm-work-period-error" className="farm-work-period-error" role="alert">{error}</p>
        : <p className="farm-work-period-range"><FarmIcon name="calendar" /><span>{range.from.replaceAll("-", ".")} ~ {range.to.replaceAll("-", ".")}</span></p>}
    </section>
  );
}
