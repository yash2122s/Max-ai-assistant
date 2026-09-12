import { describe, it, expect } from "vitest";
import {
  useTasksStore,
  applyTaskCreated,
  applyTaskStarted,
  applyStepStarted,
  applyStepCompleted,
  applyStepFailed,
  applyTaskCompleted,
  applyTaskFailed,
} from "@/store/useTasksStore";

describe("tasks store event adapters", () => {
  it("builds a task from create/start events", () => {
    applyTaskCreated({ task_id: "t1", goal: "Open Calculator", total_steps: 2 });
    applyTaskStarted({ task_id: "t1", goal: "Open Calculator", total_steps: 2 });
    const task = useTasksStore.getState().tasksById["t1"];
    expect(task.status).toBe("RUNNING");
    expect(task.goal).toBe("Open Calculator");
    expect(task.total_steps).toBe(2);
  });

  it("tracks step transitions", () => {
    applyTaskCreated({ task_id: "t1", goal: "Do the thing", total_steps: 1 });
    useTasksStore.getState().upsert({
      schema_version: "1.0",
      task_id: "t1",
      goal: "Do the thing",
      status: "RUNNING",
      created_at: 1,
      updated_at: 1,
      priority: 1,
      source_device: "windows",
      target_device: "windows",
      risk_level: "SAFE",
      current_step: 1,
      total_steps: 1,
      steps: [
        {
          step_id: "s1",
          task_id: "t1",
          sequence: 1,
          action: "core.app:launch",
          arguments: {},
          status: "PENDING",
          attempts: 0,
          max_attempts: 3,
          timeout_s: 60,
          risk_level: "SAFE",
          retryable: true,
          verification: { type: "none", target: null, timeout_seconds: 3 },
        },
      ],
      context: {},
    });
    applyStepStarted({ task_id: "t1", step_id: "s1", sequence: 1, action: "core.app:launch", attempt: 1 });
    expect(useTasksStore.getState().tasksById["t1"].steps[0].status).toBe("RUNNING");
    applyStepCompleted({ task_id: "t1", step_id: "s1", sequence: 1, duration_ms: 50, attempts: 1 });
    expect(useTasksStore.getState().tasksById["t1"].steps[0].status).toBe("SUCCEEDED");
  });

  it("marks steps failed with the error message", () => {
    applyTaskCreated({ task_id: "t1", goal: "g", total_steps: 1 });
    useTasksStore.getState().upsert({
      schema_version: "1.0",
      task_id: "t1",
      goal: "g",
      status: "RUNNING",
      created_at: 1,
      updated_at: 1,
      priority: 1,
      source_device: "windows",
      target_device: "windows",
      risk_level: "SAFE",
      current_step: 1,
      total_steps: 1,
      steps: [
        {
          step_id: "s1",
          task_id: "t1",
          sequence: 1,
          action: "x",
          arguments: {},
          status: "PENDING",
          attempts: 0,
          max_attempts: 3,
          timeout_s: 60,
          risk_level: "SAFE",
          retryable: true,
          verification: { type: "none", target: null, timeout_seconds: 3 },
        },
      ],
      context: {},
    });
    applyStepFailed({ task_id: "t1", step_id: "s1", sequence: 1, error: "verification failed" });
    const step = useTasksStore.getState().tasksById["t1"].steps[0];
    expect(step.status).toBe("FAILED");
    expect(step.error?.message).toContain("verification failed");
  });

  it("updates task terminal states", () => {
    applyTaskCreated({ task_id: "t1", goal: "g", total_steps: 1 });
    applyTaskCompleted({ task_id: "t1", goal: "g", duration_ms: 200 });
    expect(useTasksStore.getState().tasksById["t1"].status).toBe("COMPLETED");

    applyTaskCreated({ task_id: "t2", goal: "g2", total_steps: 1 });
    applyTaskFailed({ task_id: "t2", error: "engine blew up" });
    expect(useTasksStore.getState().tasksById["t2"].status).toBe("FAILED");
    expect(useTasksStore.getState().tasksById["t2"].error?.message).toBe("engine blew up");
  });
});