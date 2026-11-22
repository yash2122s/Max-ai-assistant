"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.assertAdmin = assertAdmin;
exports.getOverviewMetrics = getOverviewMetrics;
exports.getUsersList = getUsersList;
exports.getUserDetail = getUserDetail;
exports.getLiveActivity = getLiveActivity;
exports.getErrorsAndPerformance = getErrorsAndPerformance;
exports.generateCsvExport = generateCsvExport;
const https_1 = require("firebase-functions/v2/https");
function assertAdmin(auth) {
    if (!auth || auth.token?.admin !== true) {
        throw new https_1.HttpsError('permission-denied', 'Administrative privileges required.');
    }
}
async function getOverviewMetrics(db) {
    const now = Date.now();
    const fiveMinutesAgo = new Date(now - 5 * 60 * 1000);
    const oneDayAgo = new Date(now - 24 * 60 * 60 * 1000);
    const sevenDaysAgo = new Date(now - 7 * 24 * 60 * 60 * 1000);
    const thirtyDaysAgo = new Date(now - 30 * 24 * 60 * 60 * 1000);
    // Read overview aggregate
    const overviewSnap = await db.collection('adminAggregates').doc('overview').get();
    const overviewData = overviewSnap.exists ? overviewSnap.data() || {} : {};
    // Active sessions in last 5 minutes
    const activeSessionsSnap = await db.collection('userSessions')
        .where('isActive', '==', true)
        .where('lastHeartbeatAt', '>=', fiveMinutesAgo)
        .limit(500)
        .get();
    // Active users count
    const dauSnap = await db.collection('userAnalytics')
        .where('lastActiveAt', '>=', oneDayAgo)
        .get();
    const wauSnap = await db.collection('userAnalytics')
        .where('lastActiveAt', '>=', sevenDaysAgo)
        .get();
    const mauSnap = await db.collection('userAnalytics')
        .where('lastActiveAt', '>=', thirtyDaysAgo)
        .get();
    // Total registered users count
    const allUsersSnap = await db.collection('userAnalytics').get();
    const totalUsers = allUsersSnap.size;
    // Daily trends from dailyAnalytics
    const dailySnap = await db.collection('dailyAnalytics')
        .orderBy('date', 'desc')
        .limit(30)
        .get();
    const dailyTrends = dailySnap.docs.map(doc => doc.data()).reverse();
    // Calculate average latency from recent events with durationMs
    const latencySnap = await db.collection('activityEvents')
        .where('durationMs', '>', 0)
        .orderBy('durationMs', 'desc')
        .limit(100)
        .get();
    let totalLatency = 0;
    let latencyCount = 0;
    latencySnap.forEach(doc => {
        const d = doc.data().durationMs;
        if (typeof d === 'number') {
            totalLatency += d;
            latencyCount++;
        }
    });
    const avgLatencyMs = latencyCount > 0 ? Math.round(totalLatency / latencyCount) : 0;
    return {
        totalUsers,
        activeUsers: {
            dau: dauSnap.size,
            wau: wauSnap.size,
            mau: mauSnap.size,
        },
        activeSessionsCount: activeSessionsSnap.size,
        totalAiRequests: overviewData.totalAiRequests || 0,
        successfulAiRequests: overviewData.successfulAiRequests || 0,
        failedAiRequests: overviewData.failedAiRequests || 0,
        totalToolExecutions: overviewData.totalToolExecutions || 0,
        failedToolExecutions: overviewData.failedToolExecutions || 0,
        totalVoiceSessions: overviewData.totalVoiceSessions || 0,
        avgLatencyMs,
        dailyTrends,
        heartbeatWindowMinutes: 5,
        timestamp: now
    };
}
async function getUsersList(db, options) {
    const fetchLimit = Math.min(options.limit || 20, 100);
    let query = db.collection('userAnalytics')
        .orderBy('lastActiveAt', 'desc')
        .limit(fetchLimit + 1);
    if (options.startAfter) {
        const startDoc = await db.collection('userAnalytics').doc(options.startAfter).get();
        if (startDoc.exists) {
            query = query.startAfter(startDoc);
        }
    }
    const snap = await query.get();
    const docs = snap.docs;
    const hasMore = docs.length > fetchLimit;
    const targetDocs = hasMore ? docs.slice(0, fetchLimit) : docs;
    // Enrich with user licenses if available
    const userList = [];
    for (const doc of targetDocs) {
        const data = doc.data();
        const uid = doc.id;
        // Check license in 'licenses' collection
        let licensePlan = 'FREE';
        let licenseStatus = 'INACTIVE';
        try {
            const licDoc = await db.collection('licenses').doc(uid).get();
            if (licDoc.exists) {
                const lic = licDoc.data();
                licensePlan = lic?.plan || 'STANDARD';
                licenseStatus = lic?.status || 'ACTIVE';
            }
        }
        catch {
            // Ignore license lookup errors
        }
        const item = {
            uid,
            appVersion: data.appVersion || 'Unknown',
            lastFeatureUsed: data.lastFeatureUsed || 'None',
            totalAiRequests: data.totalAiRequests || 0,
            totalToolExecutions: data.totalToolExecutions || 0,
            totalVoiceSessions: data.totalVoiceSessions || 0,
            lastActiveAt: data.lastActiveAt?.toMillis ? data.lastActiveAt.toMillis() : (data.lastActiveAt || null),
            licensePlan,
            licenseStatus
        };
        // Filter by search query if provided
        if (options.searchQuery) {
            const q = options.searchQuery.toLowerCase();
            if (!uid.toLowerCase().includes(q)) {
                continue;
            }
        }
        // Filter by plan if provided
        if (options.filterPlan && options.filterPlan !== 'ALL') {
            if (licensePlan.toUpperCase() !== options.filterPlan.toUpperCase()) {
                continue;
            }
        }
        userList.push(item);
    }
    return { users: userList, hasMore };
}
async function getUserDetail(db, targetUid) {
    if (!targetUid) {
        throw new https_1.HttpsError('invalid-argument', 'Missing targetUid');
    }
    const userDoc = await db.collection('userAnalytics').doc(targetUid).get();
    const userData = userDoc.exists ? userDoc.data() : { userId: targetUid };
    // Get license
    let license = null;
    try {
        const licSnap = await db.collection('licenses').doc(targetUid).get();
        if (licSnap.exists) {
            license = licSnap.data();
        }
    }
    catch {
        // Ignore
    }
    // Get last 20 sessions
    const sessionsSnap = await db.collection('userSessions')
        .where('userId', '==', targetUid)
        .orderBy('startedAt', 'desc')
        .limit(20)
        .get();
    const sessions = sessionsSnap.docs.map(doc => {
        const d = doc.data();
        return {
            sessionId: doc.id,
            startedAt: d.startedAt?.toMillis ? d.startedAt.toMillis() : null,
            endedAt: d.endedAt?.toMillis ? d.endedAt.toMillis() : null,
            lastHeartbeatAt: d.lastHeartbeatAt?.toMillis ? d.lastHeartbeatAt.toMillis() : null,
            durationMs: d.durationMs || null,
            isActive: !!d.isActive,
            appVersion: d.appVersion || 'Unknown'
        };
    });
    // Get last 50 activity events
    const eventsSnap = await db.collection('activityEvents')
        .where('userId', '==', targetUid)
        .orderBy('serverTimestamp', 'desc')
        .limit(50)
        .get();
    const events = eventsSnap.docs.map(doc => {
        const d = doc.data();
        return {
            eventId: doc.id,
            eventType: d.eventType,
            feature: d.feature,
            status: d.status,
            durationMs: d.durationMs || null,
            errorCategory: d.errorCategory || null,
            appVersion: d.appVersion,
            platform: d.platform,
            serverTimestamp: d.serverTimestamp?.toMillis ? d.serverTimestamp.toMillis() : null,
            metadata: d.metadata || {}
        };
    });
    return {
        user: {
            uid: targetUid,
            ...userData,
            lastActiveAt: userData?.lastActiveAt?.toMillis ? (userData?.lastActiveAt).toMillis() : null
        },
        license,
        sessions,
        events
    };
}
async function getLiveActivity(db, limitCount = 50) {
    const maxLimit = Math.min(limitCount, 100);
    const snap = await db.collection('activityEvents')
        .orderBy('serverTimestamp', 'desc')
        .limit(maxLimit)
        .get();
    return snap.docs.map(doc => {
        const d = doc.data();
        return {
            eventId: doc.id,
            userId: d.userId,
            eventType: d.eventType,
            feature: d.feature,
            status: d.status,
            durationMs: d.durationMs || null,
            errorCategory: d.errorCategory || null,
            appVersion: d.appVersion,
            platform: d.platform,
            serverTimestamp: d.serverTimestamp?.toMillis ? d.serverTimestamp.toMillis() : null,
            metadata: d.metadata || {}
        };
    });
}
async function getErrorsAndPerformance(db) {
    // Recent 50 failed events
    const errorEventsSnap = await db.collection('activityEvents')
        .where('status', '==', 'FAILURE')
        .orderBy('serverTimestamp', 'desc')
        .limit(50)
        .get();
    const errorCategories = {};
    const recentErrors = errorEventsSnap.docs.map(doc => {
        const d = doc.data();
        const cat = d.errorCategory || 'UNKNOWN_ERROR';
        errorCategories[cat] = (errorCategories[cat] || 0) + 1;
        return {
            eventId: doc.id,
            userId: d.userId,
            feature: d.feature,
            eventType: d.eventType,
            errorCategory: cat,
            durationMs: d.durationMs || null,
            serverTimestamp: d.serverTimestamp?.toMillis ? d.serverTimestamp.toMillis() : null,
            metadata: d.metadata || {}
        };
    });
    // Latency samples
    const latencySnap = await db.collection('activityEvents')
        .where('durationMs', '>', 0)
        .orderBy('durationMs', 'desc')
        .limit(100)
        .get();
    const latencyBuckets = {
        '<200ms': 0,
        '200ms-500ms': 0,
        '500ms-1s': 0,
        '1s-3s': 0,
        '>3s': 0
    };
    latencySnap.forEach(doc => {
        const dur = doc.data().durationMs;
        if (typeof dur === 'number') {
            if (dur < 200)
                latencyBuckets['<200ms']++;
            else if (dur < 500)
                latencyBuckets['200ms-500ms']++;
            else if (dur < 1000)
                latencyBuckets['500ms-1s']++;
            else if (dur < 3000)
                latencyBuckets['1s-3s']++;
            else
                latencyBuckets['>3s']++;
        }
    });
    return {
        recentErrors,
        errorCategories,
        latencyBuckets,
        totalErrorSamples: recentErrors.length,
        timestamp: Date.now()
    };
}
function csvCell(value) {
    if (typeof value === 'number' && Number.isFinite(value))
        return String(value);
    const text = String(value ?? '');
    const literal = /^\s*[=+@-]|^[\t\r\n]/.test(text) ? "'" + text : text;
    return '"' + literal.replace(/"/g, '""') + '"';
}
async function generateCsvExport(db, type) {
    if (type === 'users') {
        const snap = await db.collection('userAnalytics').orderBy('lastActiveAt', 'desc').limit(500).get();
        const headers = ['UID', 'AppVersion', 'LastFeature', 'TotalAIRequests', 'TotalToolExecutions', 'LastActive'];
        const rows = [headers.join(',')];
        snap.forEach(doc => {
            const d = doc.data();
            const lastActive = d.lastActiveAt?.toDate ? d.lastActiveAt.toDate().toISOString() : '';
            rows.push([
                doc.id, d.appVersion, d.lastFeatureUsed,
                d.totalAiRequests || 0,
                d.totalToolExecutions || 0,
                lastActive
            ].map(csvCell).join(','));
        });
        return rows.join('\n');
    }
    else {
        const snap = await db.collection('activityEvents').orderBy('serverTimestamp', 'desc').limit(1000).get();
        const headers = ['EventID', 'UserID', 'EventType', 'Feature', 'Status', 'DurationMs', 'ErrorCategory', 'Timestamp'];
        const rows = [headers.join(',')];
        snap.forEach(doc => {
            const d = doc.data();
            const ts = d.serverTimestamp?.toDate ? d.serverTimestamp.toDate().toISOString() : '';
            rows.push([
                doc.id, d.userId, d.eventType, d.feature, d.status,
                d.durationMs || 0,
                d.errorCategory, ts
            ].map(csvCell).join(','));
        });
        return rows.join('\n');
    }
}
//# sourceMappingURL=admin.js.map