import { csvCell, recentEventLimit, summarizeLatency } from "../../../functions/src/reporting";
import { httpsCallable } from "firebase/functions";
import {
  collection,
  doc,
  getDoc,
  getDocs,
  getCountFromServer,
  query,
  where,
  orderBy,
  startAfter,
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
  avgLatencyMs: number | null;
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

async function readRecentLatency() {
  const events = await getDocs(query(collection(firestore, "activityEvents"),
    orderBy("serverTimestamp", "desc"), fsLimit(recentEventLimit)));
  return summarizeLatency(events.docs.map(doc => doc.data()));
}

export async function getOverview(): Promise<OverviewMetrics> {
  try {
    const fn = httpsCallable<void, OverviewMetrics>(functions, "getAdminOverview");
    const res = await fn();
    return res.data;
  } catch {
    // Fallback direct Firestore reads if callable function is unavailable
    const now = Date.now();
    const users = collection(firestore, "userAnalytics");
    const activeCount = (days: number) => getCountFromServer(query(users,
      where("lastActiveAt", ">=", new Date(now - days * 86400000))));
    const [overviewDoc, sessions, allUsers, dau, wau, mau, dailySnap, latency] = await Promise.all([
      getDoc(doc(firestore, "adminAggregates", "overview")),
      getCountFromServer(query(collection(firestore, "userSessions"), where("isActive", "==", true),
        where("lastHeartbeatAt", ">=", new Date(now - 300000)))),
      getCountFromServer(users), activeCount(1), activeCount(7), activeCount(30),
      getDocs(query(collection(firestore, "dailyAnalytics"), orderBy("date", "desc"), fsLimit(30))),
      readRecentLatency()
    ]);
    const data = overviewDoc.data() || {};
    const dailyTrends = dailySnap.docs.map(d => d.data() as OverviewMetrics["dailyTrends"][0]).reverse();

    return {
      totalUsers: allUsers.data().count,
      activeUsers: {
        dau: dau.data().count,
        wau: wau.data().count,
        mau: mau.data().count,
      },
      activeSessionsCount: sessions.data().count,
      totalAiRequests: data.totalAiRequests || 0,
      successfulAiRequests: data.successfulAiRequests || 0,
      failedAiRequests: data.failedAiRequests || 0,
      totalToolExecutions: data.totalToolExecutions || 0,
      failedToolExecutions: data.failedToolExecutions || 0,
      totalVoiceSessions: data.totalVoiceSessions || 0,
      avgLatencyMs: latency.avgLatencyMs,
      dailyTrends,
      heartbeatWindowMinutes: 5,
      timestamp: Date.now()
    };
  }
}

export async function getUsers(options?: {
  limit?: number;
  startAfter?: string;
  searchQuery?: string;
  filterPlan?: string;
}): Promise<{ users: UserItem[]; hasMore: boolean; nextCursor: string | null }> {
  try {
    const fn = httpsCallable<typeof options, { users: UserItem[]; hasMore: boolean; nextCursor: string | null }>(functions, "getAdminUsers");
    const res = await fn(options);
    return res.data;
  } catch {
    if (options?.filterPlan && options.filterPlan !== "ALL") throw new Error("License filtering requires the admin service.");
    const collectionRef = collection(firestore, "userAnalytics");
    const pageSize = Math.min(options?.limit || 20, 100);
    const cursor = options?.startAfter ? await getDoc(doc(collectionRef, options.startAfter)) : null;
    if (cursor && !cursor.exists()) throw new Error("User list changed. Refresh to restart pagination.");
    const snap = await getDocs(query(collectionRef, orderBy("lastActiveAt", "desc"),
      ...(cursor ? [startAfter(cursor)] : []), fsLimit(pageSize + 1)));
    const page = snap.docs.slice(0, pageSize);
    const hasMore = snap.size > pageSize;
    const users: UserItem[] = page.map(d => {
      const data = d.data();
      return {
        uid: d.id,
        appVersion: data.appVersion || "1.0.0",
        lastFeatureUsed: data.lastFeatureUsed || "None",
        totalAiRequests: data.totalAiRequests || 0,
        totalToolExecutions: data.totalToolExecutions || 0,
        totalVoiceSessions: data.totalVoiceSessions || 0,
        lastActiveAt: data.lastActiveAt?.toMillis ? data.lastActiveAt.toMillis() : null,
        licensePlan: "UNKNOWN",
        licenseStatus: "UNKNOWN"
      };
    });
    const search = options?.searchQuery?.toLowerCase() || "";
    return { users: users.filter(user => user.uid.toLowerCase().includes(search)),
      hasMore, nextCursor: hasMore ? page[page.length - 1].id : null };
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
      durationMs: d.data().durationMs ?? null,
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
      durationMs: d.data().durationMs ?? null,
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
        durationMs: data.durationMs ?? null,
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
      errorCategory: d.data().errorCategory || "UNKNOWN_ERROR",
      durationMs: d.data().durationMs ?? null,
      serverTimestamp: d.data().serverTimestamp?.toMillis ? d.data().serverTimestamp.toMillis() : Date.now(),
      metadata: d.data().metadata || {}
    }));

    const { latencyBuckets } = await readRecentLatency();
    const errorCategories: Record<string, number> = Object.create(null);
    for (const { errorCategory } of recentErrors) {
      errorCategories[errorCategory] = (errorCategories[errorCategory] || 0) + 1;
    }

    return {
      recentErrors,
      errorCategories,
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
      const snap = await getDocs(query(collection(firestore, "userAnalytics"), orderBy("lastActiveAt", "desc"), fsLimit(500)));
      const headers = ["UID", "AppVersion", "LastFeature", "TotalAIRequests", "TotalToolExecutions", "LastActive"];
      const rows = [headers.join(",")];
      snap.forEach(d => {
        const u = d.data();
        rows.push([d.id, u.appVersion, u.lastFeatureUsed, u.totalAiRequests || 0,
          u.totalToolExecutions || 0, u.lastActiveAt?.toDate?.().toISOString()].map(csvCell).join(","));
      });
      return { csv: rows.join("\n"), filename: `max-users-report-${Date.now()}.csv` };
    } else {
      const snap = await getDocs(query(collection(firestore, "activityEvents"), orderBy("serverTimestamp", "desc"), fsLimit(1000)));
      const headers = ["EventID", "UserID", "EventType", "Feature", "Status", "DurationMs", "ErrorCategory", "Timestamp"];
      const rows = [headers.join(",")];
      snap.forEach(d => {
        const ev = d.data();
        rows.push([d.id, ev.userId, ev.eventType, ev.feature, ev.status, ev.durationMs || 0,
          ev.errorCategory, ev.serverTimestamp?.toDate?.().toISOString()].map(csvCell).join(","));
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
