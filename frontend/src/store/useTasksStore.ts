import { create } from "zustand";
import type {
  StepStateValue,
  TaskCancelledPayload,
  TaskCompletedPayload,
  TaskCreatedPayload,
  TaskDto,
  TaskFailedPayload,
  TaskStartedPayload,
  StepStartedPayload,
  StepCompletedPayload,
  StepFailedPayload,
  StepRetryingPayload,
  TaskStateValue,
} from "@/lib/types";

interface TasksState {
  tasksById: Record<string, TaskDto>;
  order: string[]; // most recent first
  hydrated: boolean;

  setTasks: (tasks: TaskDto[]) => void;
  upsert: (task: TaskDto) => void;
  patchStatus: (taskId: string, status: TaskStateValue, extra?: Partial<TaskDto>) => void;
  patchStep: (
    taskId: string,
    stepId: string,
    status: StepStateValue,
    extra?: { attempts?: number; error?: { code?: string; message?: string } },
  ) => void;
  touch: (taskId: string) => void;
}

export const useTasksStore = create<TasksState>((set) => ({
  tasksById: {},
  order: [],
  hydrated: false,

  setTasks: (tasks) =>
    set((state) => {
      const byId = { ...state.tasksById };
      const order: string[] = [];
      for (const t of tasks) {
        byId[t.task_id] = { ...(byId[t.task_id] ?? {}), ...t };
        order.push(t.task_id);
      }
      for (const id of state.order) {
        if (!order.includes(id) && byId[id]) order.push(id);
      }
      return { tasksById: byId, order, hydrated: true };
    }),

  upsert: (task) =>
    set((state) => {
      const existing = state.tasksById[task.task_id];
      const merged: TaskDto = existing ? { ...existing, ...task } : task;
      const order = state.order.includes(task.task_id)
        ? state.order
        : [task.task_id, ...state.order];
      return { tasksById: { ...state.tasksById, [task.task_id]: merged }, order };
    }),

  patchStatus: (taskId, status, extra = {}) =>
    set((state) => {
      const existing = state.tasksById[taskId];
      if (!existing) {
        const partial = {
          schema_version: "1.0",
          task_id: taskId,
          goal: extra.goal ?? "Unknown goal",
          status,
          created_at: Date.now() / 1000,
          updated_at: Date.now() / 1000,
          priority: 1,
          source_device: "windows",
          target_device: "windows",
          risk_level: "SAFE",
          current_step: 0,
          total_steps: extra.total_steps ?? 0,
          steps: [],
          context: {},
          ...extra,
        } as unknown as TaskDto;
        return {
          tasksById: { ...state.tasksById, [taskId]: partial },
          order: state.order.includes(taskId) ? state.order : [taskId, ...state.order],
        };
      }
      return {
        tasksById: {
          ...state.tasksById,
          [taskId]: {
            ...existing,
            status,
            updated_at: Date.now() / 1000,
            ...(extra.total_steps != null ? { total_steps: extra.total_steps } : {}),
            ...(extra.current_step != null ? { current_step: extra.current_step } : {}),
            ...(extra.result ? { result: extra.result } : {}),
            ...(extra.error ? { error: extra.error } : {}),
          },
        },
      };
    }),

  patchStep: (taskId, stepId, status, extra = {}) =>
    set((state) => {
      const task = state.tasksById[taskId];
      if (!task) return {};
      const steps = task.steps.map((s) =>
        s.step_id === stepId
          ? {
              ...s,
              status,
              ...(extra.attempts != null ? { attempts: extra.attempts } : {}),
              ...(extra.error ? { error: extra.error } : {}),
            }
          : s,
      );
      return {
        tasksById: {
          ...state.tasksById,
          [taskId]: { ...task, steps, updated_at: Date.now() / 1000 },
        },
      };
    }),

  touch: (taskId) =>
    set((state) => {
      if (state.order.includes(taskId)) return {};
      return { order: [taskId, ...state.order] };
    }),
}));

// Event → store adapters (pure, unit-testable)
export function applyTaskCreated(payload: TaskCreatedPayload): void {
  const store = useTasksStore.getState();
  store.patchStatus(payload.task_id, "CREATED", {
    goal: payload.goal,
    total_steps: payload.total_steps,
  });
  store.touch(payload.task_id);
}

export function applyTaskStarted(payload: TaskStartedPayload): void {
  useTasksStore.getState().patchStatus(payload.task_id, "RUNNING", {
    goal: payload.goal,
    total_steps: payload.total_steps,
  });
}

export function applyStepStarted(payload: StepStartedPayload): void {
  useTasksStore.getState().patchStep(payload.task_id, payload.step_id, "RUNNING", {
    attempts: payload.attempt,
  });
}

export function applyStepCompleted(payload: StepCompletedPayload): void {
  useTasksStore.getState().patchStep(payload.task_id, payload.step_id, "SUCCEEDED", {
    attempts: payload.attempts,
  });
}

export function applyStepFailed(payload: StepFailedPayload): void {
  useTasksStore.getState().patchStep(payload.task_id, payload.step_id, "FAILED", {
    error: { code: "STEP_FAILED", message: payload.error ?? payload.reason ?? "Step failed" },
  });
}

export function applyStepRetrying(payload: StepRetryingPayload): void {
  useTasksStore.getState().patchStep(payload.task_id, payload.step_id, "RETRYING", {
    attempts: payload.attempt,
  });
}

export function applyTaskCompleted(payload: TaskCompletedPayload): void {
  useTasksStore.getState().patchStatus(payload.task_id, "COMPLETED", {
    goal: payload.goal,
    result: { status: "success", duration_ms: payload.duration_ms },
  });
}

export function applyTaskFailed(payload: TaskFailedPayload): void {
  useTasksStore.getState().patchStatus(payload.task_id, "FAILED", {
    error: { code: "TASK_FAILED", message: payload.error ?? "Task failed" },
  });
}

export function applyTaskCancelled(payload: TaskCancelledPayload): void {
  useTasksStore.getState().patchStatus(payload.task_id, "CANCELLED", {
    error: { code: "TASK_CANCELLED", message: payload.reason ?? "Cancelled" },
  });
}