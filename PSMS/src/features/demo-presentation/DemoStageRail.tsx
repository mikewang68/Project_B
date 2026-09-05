export type DemoStageRailItem<StepId extends string> = Readonly<{
  id: StepId;
  label: string;
  detail?: string;
}>;

export type DemoStageRailProps<StepId extends string> = {
  ariaLabel: string;
  currentStepIndex: number;
  stages: readonly DemoStageRailItem<StepId>[];
};

export default function DemoStageRail<StepId extends string>({
  ariaLabel,
  currentStepIndex,
  stages,
}: DemoStageRailProps<StepId>) {
  return (
    <ol className="demo-stage-rail" aria-label={ariaLabel}>
      {stages.map((stage, index) => {
        const state = index < currentStepIndex
          ? 'completed'
          : index === currentStepIndex
            ? 'current'
            : 'pending';
        return (
          <li
            key={stage.id}
            className="demo-stage-transition"
            data-state={state}
            {...(state === 'current' ? { 'aria-current': 'step' as const } : {})}
          >
            <span className="demo-stage-index">{String(index + 1).padStart(2, '0')}</span>
            <span className="demo-stage-label">{stage.label}</span>
            {stage.detail ? <span className="demo-stage-detail">{stage.detail}</span> : null}
          </li>
        );
      })}
    </ol>
  );
}
