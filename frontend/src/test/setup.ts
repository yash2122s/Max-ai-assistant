import "@testing-library/jest-dom";
import { afterEach, vi } from "vitest";
import { resetSseClientForTests } from "@/lib/sse";
import { useAgentStore } from "@/store/useAgentStore";
import { useSystemStore } from "@/store/useSystemStore";
import { useDevicesStore } from "@/store/useDevicesStore";
import { useChatStore } from "@/store/useChatStore";
import { useActivityStore } from "@/store/useActivityStore";
import { useTasksStore } from "@/store/useTasksStore";
import { useContextStore } from "@/store/useContextStore";

afterEach(() => {
  resetSseClientForTests();
  useAgentStore.setState(useAgentStore.getInitialState);
  useSystemStore.setState(useSystemStore.getInitialState);
  useDevicesStore.setState(useDevicesStore.getInitialState);
  useChatStore.setState(useChatStore.getInitialState);
  useActivityStore.setState(useActivityStore.getInitialState);
  useTasksStore.setState(useTasksStore.getInitialState);
  useContextStore.setState(useContextStore.getInitialState);
  vi.restoreAllMocks();
});