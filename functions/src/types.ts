export interface IngestEventItem {
  eventId: string;
  eventType: string;
  feature: string;
  status: 'SUCCESS' | 'FAILURE' | 'IN_PROGRESS' | 'CANCELLED' | 'TIMEOUT';
  durationMs?: number;
  sessionId: string;
  appVersion: string;
  platform?: string;
  errorCategory?: string;
  metadata?: Record<string, unknown>;
  clientTimestamp?: number;
}

export interface IngestEventsRequest {
  ownerUid?: string;
  events: IngestEventItem[];
}

export interface SessionHeartbeatRequest {
  sessionId: string;
  appVersion?: string;
}

export interface SessionEndRequest {
  sessionId: string;
}

export interface UserSummaryItem {
  userId: string;
  email?: string;
  displayName?: string;
  appVersion?: string;
  registeredAt?: number;
  lastActiveAt?: number;
  lastFeatureUsed?: string;
  totalAiRequests?: number;
  totalToolExecutions?: number;
  totalVoiceSessions?: number;
  licensePlan?: string;
  licenseStatus?: string;
}
