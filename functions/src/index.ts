import * as admin from 'firebase-admin';
import { onCall, HttpsError } from 'firebase-functions/v2/https';
import {
  processIngestEvents,
  processSessionHeartbeat,
  processSessionEnd
} from './analytics';
import {
  assertAdmin,
  getOverviewMetrics,
  getUsersList,
  getUserDetail,
  getLiveActivity,
  getErrorsAndPerformance,
  generateCsvExport
} from './admin';
import {
  IngestEventsRequest,
  SessionHeartbeatRequest,
  SessionEndRequest
} from './types';

admin.initializeApp();
const db = admin.firestore();

// -----------------------------------------------------------------------------
// USER FACING / CLIENT CALLABLES
// -----------------------------------------------------------------------------

export const ingestActivityEvents = onCall<IngestEventsRequest>(
  { cors: true },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'User must be authenticated to send activity events.');
    }
    const uid = request.auth.uid;
    const { email, name } = request.auth.token;
    return await processIngestEvents(uid, request.data, db, { email, name });
  }
);

export const sessionHeartbeat = onCall<SessionHeartbeatRequest>(
  { cors: true },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'User must be authenticated.');
    }
    await processSessionHeartbeat(request.auth.uid, request.data, db);
    return { success: true };
  }
);

export const sessionEnd = onCall<SessionEndRequest>(
  { cors: true },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'User must be authenticated.');
    }
    await processSessionEnd(request.auth.uid, request.data, db);
    return { success: true };
  }
);

// -----------------------------------------------------------------------------
// ADMIN CALLABLES (RESTRICTED VIA ADMIN CUSTOM CLAIM)
// -----------------------------------------------------------------------------

export const getAdminOverview = onCall(
  { cors: true },
  async (request) => {
    assertAdmin(request.auth);
    return await getOverviewMetrics(db);
  }
);

export const getAdminUsers = onCall<{
  limit?: number;
  startAfter?: string;
  searchQuery?: string;
  filterPlan?: string;
}>(
  { cors: true },
  async (request) => {
    assertAdmin(request.auth);
    return await getUsersList(db, request.data || {});
  }
);

export const getAdminUserDetail = onCall<{ targetUid: string }>(
  { cors: true },
  async (request) => {
    assertAdmin(request.auth);
    return await getUserDetail(db, request.data?.targetUid);
  }
);

export const getAdminLiveActivity = onCall<{ limit?: number }>(
  { cors: true },
  async (request) => {
    assertAdmin(request.auth);
    return await getLiveActivity(db, request.data?.limit || 50);
  }
);

export const getAdminErrors = onCall(
  { cors: true },
  async (request) => {
    assertAdmin(request.auth);
    return await getErrorsAndPerformance(db);
  }
);

export const exportAdminReport = onCall<{ type: 'users' | 'activity' }>(
  { cors: true },
  async (request) => {
    assertAdmin(request.auth);
    const type = request.data?.type === 'users' ? 'users' : 'activity';
    const csv = await generateCsvExport(db, type);
    return { csv, type, filename: `max-${type}-report-${Date.now()}.csv` };
  }
);

export const setAdminRole = onCall<{ targetUid: string; isAdmin: boolean }>(
  { cors: true },
  async (request) => {
    assertAdmin(request.auth);
    const { targetUid, isAdmin } = request.data ?? {};
    if (!targetUid || typeof targetUid !== 'string') {
      throw new HttpsError('invalid-argument', 'Missing or invalid targetUid');
    }
    if (typeof isAdmin !== 'boolean') {
      throw new HttpsError('invalid-argument', 'isAdmin must be a boolean');
    }

    // Safety guard: Protect root admin from accidental lockout
    const ROOT_ADMIN_UID = 'RhBWlfWGKwNVnoLWu2iihHm4riG3';
    if (targetUid === ROOT_ADMIN_UID && !isAdmin) {
      throw new HttpsError('permission-denied', 'Cannot revoke privileges from primary root administrator.');
    }

    const auth = admin.auth();
    const account = await auth.getUser(targetUid);
    await auth.setCustomUserClaims(targetUid, { ...account.customClaims, admin: isAdmin });
    return { success: true, targetUid, isAdmin };
  }
);
