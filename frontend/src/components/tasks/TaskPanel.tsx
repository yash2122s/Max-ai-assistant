import { ListChecks, CircleDot, RotateCcw, AlertTriangle, CheckCircle2, Lock } from "lucide-react";
import { useTasksStore } from "@/store/useTasksStore";
import type { TaskDto, TaskStateValue, StepStateValue } from "@/lib/types";
import { GlassPanel } from "@/components/common/GlassPanel";
import { Badge } from "@/components/common/Badge";
import { ProgressBar } from "@/components/common/ProgressBar";
import { cn } from "@/lib/cn";

const TASK_TONE: Record<string, "mint" | "amber" | "rose" | "accent" | "neutral"> = {
  RUNNING: "accent",
  VERIFYING: "accent",
  RETRYING: "amber",
  WAITING: "amber",
  PAUSED: "amber",
  BLOCKED: "amber",
  COMPLETED: "mint",
  FAILED: "rose",
  CANCELLED: "rose",
  EXPIRED: "rose",
  INTERRUPTED: "rose",
  CREATED: "neutral",
};

function isTerminal(status: TaskStateValue): boolean {
  return ["COMPLETED", "FAILED", "CANCELLED", "EXPIRED", "INTERRUPTED"].includes(status);
}

function StepIcon({ status }: { status: StepStateValue }) {
  switch (status) {
    case "SUCCEEDED":
      return <CheckCircle2 className="h-3.5 w-3.5 text-mint" aria-hidden />;
    case "FAILED":
    case "CANCELLED":
      return <AlertTriangle className="h-3.5 w-3.5 text-rose" aria-hidden />;
    case "BLOCKED":
      return <Lock className="h-3.5 w-3.5 text-amber" aria-hidden />;
    case "RUNNING":
    case "VERIFYING":
      return <CircleDot className="h-3.5 w-3.5 animate-pulse text-accent" aria-hidden />;
    case "RETRYING":
      return <RotateCcw className="h-3.5 w-3.5 animate-spin text-amber" aria-hidden />;
    default:
      return <CircleDot className="h-3.5 w-3.5 text-ink-faint" aria-hidden />;
  }
}

export function TaskPanel() {
  const order = useTasksStore((s) => s.order);
  const tasks = useTasksStore((s) => s.tasksById);

  // Surface the most relevant task: the last non-terminal task, else the most recent.
  const tasksList = order
    .map((id) => tasks[id])
    .filter((t): t is TaskDto => Boolean(t));
  const active =
    tasksList.find((t) => !isTerminal(t.status)) ?? tasksList[0] ?? null;

  return (
    <GlassPanel
      title={
        <span className="flex items-center gap-1.5">
          <ListChecks className="h-3.5 w-3.5 text-accent/70" aria-hidden />
          Task engine
        </span>
      }
      className="min-h-0 shrink-0"
      pad={false}
    >
      {!active ? (
        <p className="p-3 text-[11.5px] leading-relaxed text-ink-faint">
          No tasks tracked. Tasks executed by the engine (via the windows_agent task actions) appear here in real time from
          task_* / step_* events.
        </p>
      ) : (
        <TaskView task={active} />
      )}
    </GlassPanel>
  );
}

function TaskView({ task }: { task: TaskDto }) {
  const running = !isTerminal(task.status);
  const progress =
    task.total_steps > 0 ? Math.round((task.current_step / task.total_steps) * 100) : 0;

  return (
    <div className="space-y-2.5 p-3">
      <div className="flex items-start justify-between gap-2">
        <p className="min-w-0 text-[12.5px] font-medium leading-snug text-ink">{task.goal}</p>
        <Badge tone={TASK_TONE[task.status] ?? "neutral"} className="shrink-0">
          {task.status}
        </Badge>
      </div>

      <div className="flex items-center gap-2">
        <ProgressBar
          value={progress}
          tone={task.status === "FAILED" || task.status === "CANCELLED" ? "rose" : task.status === "COMPLETED" ? "mint" : "accent"}
          ariaLabel="Task progress"
        />
        <span className="shrink-0 font-mono text-[10px] text-ink-faint">
          {task.current_step}/{task.total_steps}
        </span>
      </div>

      {task.error && (
        <p className="break-words rounded-lg border border-rose/25 bg-rose/10 px-2 py-1.5 font-mono text-[11px] text-rose" role="alert">
          {task.error.message ?? task.error.code}
        </p>
      )}

      {task.result?.duration_ms != null && task.status === "COMPLETED" && (
        <p className="font-mono text-[10.5px] text-ink-faint">
          Completed in {(task.result.duration_ms as number).toFixed(0)} ms
        </p>
      )}

      {task.steps.length > 0 && (
        <ul className="space-y-1">
          {task.steps.map((step) => (
            <li key={step.step_id} className="flex items-center gap-2">
              <StepIcon status={step.status} />
              <span className="min-w-0 flex-1">
                <span
                  className={cn(
                    "block truncate font-mono text-[11px]",
                    step.status === "FAILED" || step.status === "CANCELLED"
                      ? "text-rose"
                      : step.status === "BLOCKED"
                        ? "text-amber"
                        : "text-ink-muted",
                  )}
                >
                  {step.sequence}. {step.action}
                </span>
                {step.error?.message && (
                  <span className="block truncate text-[10px] text-ink-faint">{step.error.message}</span>
                )}
              </span>
              {step.attempts > 1 && (
                <Badge tone="amber" className="shrink-0">x{step.attempts}</Badge>
              )}
            </li>
          ))}
        </ul>
      )}

      {running && (
        <p className="flex items-center gap-1.5 font-mono text-[10px] text-accent-soft" role="status">
          <CircleDot className="h-3 w-3 animate-pulse" aria-hidden />
          {task.status === "BLOCKED" ? "Waiting for confirmation" : "Executing…"}
        </p>
      )}
    </div>
  );
}