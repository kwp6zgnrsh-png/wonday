import { enrollmentStages, enrollmentSteps } from "./enrollmentSteps";

export default function EnrollmentProgress({ step, compact = false }: { step: number; compact?: boolean }) {
  const currentStage = enrollmentSteps[step]?.stageIndex ?? enrollmentStages.length;

  return (
    <section className={compact ? "enrollment-progress compact" : "enrollment-progress"} aria-label="가입 진행 상황">
      <h3>가입 진행 상황</h3>
      <ol>
        {enrollmentStages.map(({ title }, index) => {
          const complete = index < currentStage;
          const current = index === currentStage;

          return (
            <li
              key={title}
              className={complete ? "is-complete" : current ? "is-current" : ""}
              aria-current={current ? "step" : undefined}
            >
              <span className="stage-marker" aria-hidden="true">
                {complete && <span className="stage-check" />}
              </span>
              <span className="stage-title">{title}</span>
              {current && <span className="stage-status">진행 중</span>}
              {!current && (
                <span className="stage-status-text">{complete ? "완료" : "예정"}</span>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
