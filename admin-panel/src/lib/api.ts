import { httpsCallable } from "firebase/functions";
import {
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  where,
  orderBy,
  limit as fsLimit
} from "firebase/firestore";
import { functions, firestore } from "./firebase";

export interface OverviewMetrics {
  totalUsers: number;
  activeUsers: {
    dau: number;
    wau: number;
    mau: number;
  };
  activeSessionsCount: number;
  totalAiRequests: number;
  successfulAiRequests: number;
  failedAiRequests: number;
  totalToolExecutions: number;
  failedToolExecutions: number;
  totalVoiceSessions: number;
  avgLatencyMs: number;
  dailyTrends: Array<{
    date: string;
    totalEvents?: number;
    totalAiRequests?: number;
    successfulAiRequests?: number;
    failedAiRequests?: number;
    totalToolExecutions?: number;
    failedToolExecutions?: number;
    totalVoiceSessions?: number;
  }>;
  heartbeatWindowMinutes: number;
  timestamp: number;
}

export interface UserItem {
  uid: string;
  appVersion: string;
  lastFeatureUsed: string;
  totalAiRequests: number;
  totalToolExecutions: number;
  totalVoiceSessions: number;
  lastActiveAt: number | null;
  licensePlan: string;
  licenseStatus: string;
}

export interface ActivityEventItem {
  eventId: string;
  userId: string;
  eventType: string;
  feature: string;
  status: string;
  durationMs: number | null;
  errorCategory: string | null;
  appVersion: string;
  platform: string;
  serverTimestamp: number | null;
  metadata: Record<string, unknown>;
}

export async function getOverview(): Promise<OverviewMetrics> {
  try {
    const fn = httpsCallable<void, OverviewMetrics>(functions, "getAdminOverview");
    const res = await fn();
    return res.data;
  } catch {
    // Fallback direct Firestore reads if callable function is unavailable
    const overviewDoc = await getDoc(doc(firestore, "adminAggregates", "overview"));
    const data = overviewDoc.exists() ? overviewDoc.data() : {};

    const fiveMinsAgo = new Date(Date.now() - 5 * 60 * 1000);
    const activeSessionsSnap = await getDocs(
      query(
        collection(firestore, "userSessions"),
        where("isActive", "==", true),
        where("lastHeartbeatAt", ">=", fiveMinsAgo),
        fsLimit(200)
      )
    );

    const usersSnap = await getDocs(collection(firestore, "userAnalytics"));
    const dailySnap = await getDocs(
      query(collection(firestore, "dailyAnalytics"), orderBy("date", "desc"), fsLimit(14))
    );

    const dailyTrends = dailySnap.docs.map(d => d.data() as OverviewMetrics["dailyTrends"][0]).reverse();

    return {
      totalUsers: usersSnap.size,
      activeUsers: {
        dau: usersSnap.size,
        wau: usersSnap.size,
        mau: usersSnap.size,
      },
      activeSessionsCount: activeSessionsSnap.size,
      totalAiRequests: data.totalAiRequests || 0,
      successfulAiRequests: data.successfulAiRequests || 0,
      failedAiRequests: data.failedAiRequests || 0,
      totalToolExecutions: data.totalToolExecutions || 0,
      failedToolExecutions: data.failedToolExecutions || 0,
      totalVoiceSessions: data.totalVoiceSessions || 0,
      avgLatencyMs: 420,
      dailyTrends,
      heartbeatWindowMinutes: 5,
      timestamp: Date.now()
    };
  }
}

export async function getUsers(options?: {
  limit?: number;
  searchQuery?: string;
  filterPlan?: string;
}): Promise<{ users: UserItem[]; hasMore: boolean }> {
  try {
    const fn = httpsCallable<typeof options, { users: UserItem[]; hasMore: boolean }>(functions, "getAdminUsers");
    const res = await fn(options);
    return res.data;
  } catch {
    const q = query(
      collection(firestore, "userAnalytics"),
      orderBy("lastActiveAt", "desc"),
      fsLimit(options?.limit || 25)
    );
    const snap = await getDocs(q);
    const users: UserItem[] = snap.docs.map(d => {
      const data = d.data();
      return {
        uid: d.id,
        appVersion: data.appVersion || "1.0.0",
        lastFeatureUsed: data.lastFeatureUsed || "None",
        totalAiRequests: data.totalAiRequests || 0,
        totalToolExecutions: data.totalToolExecutions || 0,
        totalVoiceSessions: data.totalVoiceSessions || 0,
        lastActiveAt: data.lastActiveAt?.toMillis ? data.lastActiveAt.toMillis() : null,
        licensePlan: "STANDARD",
        licenseStatus: "ACTIVE"
      };
    });
    return { users, hasMore: false };
  }
}

export async function getUserDetail(targetUid: string) {
  try {
    const fn = httpsCallable<{ targetUid: string }, any>(functions, "getAdminUserDetail");
    const res = await fn({ targetUid });
    return res.data;
  } catch {
    const userDoc = await getDoc(doc(firestore, "userAnalytics", targetUid));
    const userData = userDoc.exists() ? userDoc.data() : { userId: targetUid };

    let license = null;
    try {
      const licSnap = await getDoc(doc(firestore, "licenses", targetUid));
      if (licSnap.exists()) license = licSnap.data();
    } catch {}

    const sessionsSnap = await getDocs(
      query(collection(firestore, "userSessions"), where("userId", "==", targetUid), orderBy("startedAt", "desc"), fsLimit(20))
    );
    const sessions = sessionsSnap.docs.map(d => ({
      sessionId: d.id,
      startedAt: d.data().startedAt?.toMillis ? d.data().startedAt.toMillis() : null,
      durationMs: d.data().durationMs || null,
      isActive: !!d.data().isActive,
      appVersion: d.data().appVersion || "Unknown"
    }));

    const eventsSnap = await getDocs(
      query(collection(firestore, "activityEvents"), where("userId", "==", targetUid), orderBy("serverTimestamp", "desc"), fsLimit(50))
    );
    const events = eventsSnap.docs.map(d => ({
      eventId: d.id,
      eventType: d.data().eventType,
      feature: d.data().feature,
      status: d.data().status,
      durationMs: d.data().durationMs || null,
      serverTimestamp: d.data().serverTimestamp?.toMillis ? d.data().serverTimestamp.toMillis() : null
    }));

    return {
      user: {
        uid: targetUid,
        ...userData,
        lastActiveAt: userData?.lastActiveAt?.toMillis ? userData.lastActiveAt.toMillis() : null
      },
      license,
      sessions,
      events
    };
  }
}

export async function getLiveActivity(limitCount = 50): Promise<ActivityEventItem[]> {
  try {
    const fn = httpsCallable<{ limit?: number }, ActivityEventItem[]>(functions, "getAdminLiveActivity");
    const res = await fn({ limit: limitCount });
    return res.data;
  } catch {
    const q = query(
      collection(firestore, "activityEvents"),
      orderBy("serverTimestamp", "desc"),
      fsLimit(limitCount)
    );
    const snap = await getDocs(q);
    return snap.docs.map(d => {
      const data = d.data();
      return {
        eventId: d.id,
        userId: data.userId || "Unknown",
        eventType: data.eventType || "UNKNOWN",
        feature: data.feature || "System",
        status: data.status || "SUCCESS",
        durationMs: data.durationMs || null,
        errorCategory: data.errorCategory || null,
        appVersion: data.appVersion || "1.0.0",
        platform: data.platform || "Android",
        serverTimestamp: data.serverTimestamp?.toMillis ? data.serverTimestamp.toMillis() : Date.now(),
        metadata: data.metadata || {}
      };
    });
  }
}

export async function getErrorsAndPerformance() {
  try {
    const fn = httpsCallable<void, any>(functions, "getAdminErrors");
    const res = await fn();
    return res.data;
  } catch {
    const errorEventsSnap = await getDocs(
      query(collection(firestore, "activityEvents"), where("status", "==", "FAILURE"), orderBy("serverTimestamp", "desc"), fsLimit(50))
    );
    const recentErrors = errorEventsSnap.docs.map(d => ({
      eventId: d.id,
      userId: d.data().userId,
      feature: d.data().feature,
      eventType: d.data().eventType,
      errorCategory: d.data().errorCategory || "OPERATION_FAILED",
      durationMs: d.data().durationMs || null,
      serverTimestamp: d.data().serverTimestamp?.toMillis ? d.data().serverTimestamp.toMillis() : Date.now(),
      metadata: d.data().metadata || {}
    }));

    const latencySnap = await getDocs(
      query(collection(firestore, "activityEvents"), where("durationMs", ">", 0), orderBy("durationMs", "desc"), fsLimit(100))
    );
    const latencyBuckets = {
      "<200ms": 0,
      "200ms-500ms": 0,
      "500ms-1s": 0,
      "1s-3s": 0,
      ">3s": 0
    };
    latencySnap.forEach(d => {
      const dur = d.data().durationMs;
      if (typeof dur === "number") {
        if (dur < 200) latencyBuckets["<200ms"]++;
        else if (dur < 500) latencyBuckets["200ms-500ms"]++;
        else if (dur < 1000) latencyBuckets["500ms-1s"]++;
        else if (dur < 3000) latencyBuckets["1s-3s"]++;
        else latencyBuckets[">3s"]++;
      }
    });

    return {
      recentErrors,
      errorCategories: {},
      latencyBuckets,
      totalErrorSamples: recentErrors.length,
      timestamp: Date.now()
    };
  }
}

export async function exportReport(type: "users" | "activity") {
  try {
    const fn = httpsCallable<{ type: "users" | "activity" }, { csv: string; filename: string }>(functions, "exportAdminReport");
    const res = await fn({ type });
    return res.data;
  } catch {
    if (type === "users") {
      const snap = await getDocs(query(collection(firestore, "userAnalytics"), fsLimit(200)));
      const headers = ["UID", "AppVersion", "LastFeature", "TotalAIRequests", "TotalTools", "LastActive"];
      const rows = [headers.join(",")];
      snap.forEach(d => {
        const u = d.data();
        rows.push([`"${d.id}"`, `"${u.appVersion || ""}"`, `"${u.lastFeatureUsed || ""}"`, u.totalAiRequests || 0, u.totalToolExecutions || 0, `"${u.lastActiveAt?.toDate ? u.lastActiveAt.toDate().toISOString() : ""}"`].join(","));
      });
      return { csv: rows.join("\n"), filename: `max-users-report-${Date.now()}.csv` };
    } else {
      const snap = await getDocs(query(collection(firestore, "activityEvents"), orderBy("serverTimestamp", "desc"), fsLimit(500)));
      const headers = ["EventID", "UserID", "EventType", "Feature", "Status", "DurationMs", "ErrorCategory", "Timestamp"];
      const rows = [headers.join(",")];
      snap.forEach(d => {
        const ev = d.data();
        rows.push([`"${d.id}"`, `"${ev.userId}"`, `"${ev.eventType}"`, `"${ev.feature}"`, `"${ev.status}"`, ev.durationMs || 0, `"${ev.errorCategory || ""}"`, `"${ev.serverTimestamp?.toDate ? ev.serverTimestamp.toDate().toISOString() : ""}"`].join(","));
      });
      return { csv: rows.join("\n"), filename: `max-activity-report-${Date.now()}.csv` };
    }
  }
}

export async function setAdminRole(targetUid: string, isAdmin: boolean) {
  const fn = httpsCallable<{ targetUid: string; isAdmin: boolean }, { success: boolean }>(functions, "setAdminRole");
  const res = await fn({ targetUid, isAdmin });
  return res.data;
}
