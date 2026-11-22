"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.validateEvent = validateEvent;
exports.processIngestEvents = processIngestEvents;
exports.processSessionHeartbeat = processSessionHeartbeat;
exports.processSessionEnd = processSessionEnd;
const admin = __importStar(require("firebase-admin"));
const https_1 = require("firebase-functions/v2/https");
const ALLOWED_EVENT_TYPES = new Set([
    'APP_OPEN', 'APP_FOREGROUND', 'APP_BACKGROUND', 'APP_CLOSE',
    'SESSION_START', 'SESSION_HEARTBEAT', 'SESSION_END',
    'AUTH_LOGIN', 'AUTH_SIGNUP', 'AUTH_LOGOUT', 'AUTH_ERROR',
    'CHAT_REQUEST_START', 'CHAT_REQUEST_SUCCESS', 'CHAT_REQUEST_FAILED',
    'VOICE_SESSION_START', 'VOICE_SESSION_END', 'VOICE_SESSION_ERROR',
    'TOOL_EXECUTION_START', 'TOOL_EXECUTION_SUCCESS', 'TOOL_EXECUTION_FAILED',
    'TASK_CREATED', 'TASK_SCHEDULED', 'TASK_COMPLETED', 'TASK_CANCELLED', 'TASK_FAILED',
    'MEMORY_READ', 'MEMORY_WRITE', 'MEMORY_DELETE',
    'SCREEN_VIEW', 'FEATURE_USED',
    'APP_ERROR', 'NETWORK_ERROR', 'API_ERROR', 'MEMORY_OP',
    'AI_REQUEST_START', 'AI_REQUEST_SUCCESS', 'AI_REQUEST_FAILED'
]);
const eventAliases = {
    AUTH_REGISTER: 'AUTH_SIGNUP',
    CHAT_REQUEST_COMPLETE: 'CHAT_REQUEST_SUCCESS', CHAT_REQUEST_FAIL: 'CHAT_REQUEST_FAILED',
    AI_REQUEST_COMPLETE: 'AI_REQUEST_SUCCESS', AI_REQUEST_FAIL: 'AI_REQUEST_FAILED',
    TOOL_EXECUTION_FAIL: 'TOOL_EXECUTION_FAILED', TASK_EXECUTED: 'TASK_COMPLETED',
    ERROR_REPORT: 'APP_ERROR'
};
function normalizedEventType(value) {
    const upper = value.toUpperCase();
    return eventAliases[upper] ?? upper;
}
const ALLOWED_STATUSES = new Set(['SUCCESS', 'FAILURE', 'IN_PROGRESS', 'CANCELLED', 'TIMEOUT']);
const PROHIBITED_METADATA_KEYS = new Set([
    'prompt', 'response', 'password', 'token', 'secret', 'transcript', 'message', 'audio', 'key', 'auth'
]);
function sanitizeMetadata(metadata) {
    if (!metadata || typeof metadata !== 'object')
        return {};
    const sanitized = {};
    let count = 0;
    for (const [key, val] of Object.entries(metadata)) {
        if (count >= 20)
            break;
        const lowerKey = key.toLowerCase();
        if ([...PROHIBITED_METADATA_KEYS].some(part => lowerKey.includes(part)))
            continue;
        if (typeof val === 'string') {
            sanitized[key] = val.substring(0, 500);
            count++;
        }
        else if (typeof val === 'number' || typeof val === 'boolean') {
            sanitized[key] = val;
            count++;
        }
    }
    return sanitized;
}
function validDocumentId(value) {
    return typeof value === 'string' && value.length > 0 && value.length <= 128 &&
        !value.includes('/') && value !== '.' && value !== '..' && !/^__.*__$/.test(value);
}
function validateEvent(event) {
    if (!event || typeof event !== 'object') {
        throw new https_1.HttpsError('invalid-argument', 'Event must be a non-null object');
    }
    if (!validDocumentId(event.eventId)) {
        throw new https_1.HttpsError('invalid-argument', 'Invalid or missing eventId');
    }
    const normEventType = typeof event.eventType === 'string' && normalizedEventType(event.eventType);
    if (!normEventType || !ALLOWED_EVENT_TYPES.has(normEventType)) {
        throw new https_1.HttpsError('invalid-argument', `Invalid eventType: ${event.eventType}`);
    }
    if (!event.feature || typeof event.feature !== 'string' || event.feature.length > 64) {
        throw new https_1.HttpsError('invalid-argument', 'Invalid or missing feature identifier');
    }
    const normStatus = (event.status || '').toUpperCase();
    if (!normStatus || !ALLOWED_STATUSES.has(normStatus)) {
        throw new https_1.HttpsError('invalid-argument', `Invalid status: ${event.status}`);
    }
    if (!event.sessionId || typeof event.sessionId !== 'string' || event.sessionId.length > 128) {
        throw new https_1.HttpsError('invalid-argument', 'Invalid or missing sessionId');
    }
    if (!event.appVersion || typeof event.appVersion !== 'string' || event.appVersion.length > 32) {
        throw new https_1.HttpsError('invalid-argument', 'Invalid or missing appVersion');
    }
    if (event.durationMs !== undefined) {
        if (typeof event.durationMs !== 'number' || event.durationMs < 0 || event.durationMs > 86400000) {
            throw new https_1.HttpsError('invalid-argument', 'durationMs must be a non-negative number <= 24 hours');
        }
    }
}
async function processIngestEvents(uid, data, db, profile = {}) {
    if (!data || !Array.isArray(data.events)) {
        throw new https_1.HttpsError('invalid-argument', 'Payload must contain an "events" array');
    }
    if (data.ownerUid !== undefined && data.ownerUid !== uid) {
        throw new https_1.HttpsError('permission-denied', 'Account changed before activity upload');
    }
    if (data.events.length === 0) {
        return { processed: 0, rejected: 0 };
    }
    if (data.events.length > 50) {
        throw new https_1.HttpsError('invalid-argument', 'Batch size exceeds maximum limit of 50 events');
    }
    const candidates = new Map();
    let invalid = 0;
    for (const event of data.events) {
        try {
            validateEvent(event);
            if (candidates.has(event.eventId))
                continue;
            candidates.set(event.eventId, event);
        }
        catch {
            invalid++;
        }
    }
    if (!candidates.size)
        return { processed: 0, rejected: invalid };
    const todayStr = new Date().toISOString().split('T')[0];
    const dailyDocRef = db.collection('dailyAnalytics').doc(todayStr);
    const userDocRef = db.collection('userAnalytics').doc(uid);
    const overviewDocRef = db.collection('adminAggregates').doc('overview');
    const refs = [...candidates.keys()].map(id => db.collection('activityEvents').doc(id));
    return db.runTransaction(async (transaction) => {
        const snapshots = await transaction.getAll(...refs);
        let processed = 0;
        let rejected = invalid;
        let totalAiDelta = 0;
        let successAiDelta = 0;
        let failedAiDelta = 0;
        let totalToolDelta = 0;
        let failedToolDelta = 0;
        let totalVoiceDelta = 0;
        let lastFeature = '';
        let lastAppVersion = '';
        for (const snapshot of snapshots) {
            const event = candidates.get(snapshot.id);
            if (snapshot.exists) {
                if (snapshot.get('userId') !== uid)
                    rejected++;
                continue;
            }
            try {
                const eventDocRef = snapshot.ref;
                const normEventType = normalizedEventType(event.eventType);
                const normStatus = event.status.toUpperCase();
                const payload = {
                    eventId: event.eventId,
                    userId: uid,
                    eventType: normEventType,
                    feature: event.feature,
                    status: normStatus,
                    sessionId: event.sessionId,
                    appVersion: event.appVersion,
                    platform: event.platform || 'Android',
                    serverTimestamp: admin.firestore.FieldValue.serverTimestamp(),
                    metadata: sanitizeMetadata(event.metadata)
                };
                if (event.durationMs !== undefined) {
                    payload.durationMs = event.durationMs;
                }
                if (event.errorCategory) {
                    payload.errorCategory = event.errorCategory.substring(0, 64);
                }
                if (event.clientTimestamp) {
                    payload.clientTimestamp = event.clientTimestamp;
                }
                transaction.create(eventDocRef, payload);
                processed++;
                lastFeature = event.feature;
                lastAppVersion = event.appVersion;
                if (['CHAT_REQUEST_SUCCESS', 'CHAT_REQUEST_FAILED', 'AI_REQUEST_SUCCESS', 'AI_REQUEST_FAILED'].includes(normEventType)) {
                    totalAiDelta++;
                    if (normStatus === 'SUCCESS')
                        successAiDelta++;
                    if (normStatus === 'FAILURE')
                        failedAiDelta++;
                }
                else if (normEventType === 'TOOL_EXECUTION_SUCCESS' || normEventType === 'TOOL_EXECUTION_FAILED') {
                    totalToolDelta++;
                    if (normStatus === 'FAILURE')
                        failedToolDelta++;
                }
                else if (normEventType === 'VOICE_SESSION_START') {
                    totalVoiceDelta++;
                }
            }
            catch {
                rejected++;
            }
        }
        if (processed > 0) {
            // Increment daily analytics
            transaction.set(dailyDocRef, {
                date: todayStr,
                totalEvents: admin.firestore.FieldValue.increment(processed),
                totalAiRequests: admin.firestore.FieldValue.increment(totalAiDelta),
                successfulAiRequests: admin.firestore.FieldValue.increment(successAiDelta),
                failedAiRequests: admin.firestore.FieldValue.increment(failedAiDelta),
                totalToolExecutions: admin.firestore.FieldValue.increment(totalToolDelta),
                failedToolExecutions: admin.firestore.FieldValue.increment(failedToolDelta),
                totalVoiceSessions: admin.firestore.FieldValue.increment(totalVoiceDelta),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            }, { merge: true });
            // Update user profile summary
            const userUpdates = {
                userId: uid,
                totalEvents: admin.firestore.FieldValue.increment(processed),
                lastActiveAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            };
            if (lastAppVersion)
                userUpdates.appVersion = lastAppVersion;
            if (profile.email)
                userUpdates.email = profile.email;
            if (profile.name)
                userUpdates.displayName = profile.name;
            if (lastFeature)
                userUpdates.lastFeatureUsed = lastFeature;
            if (totalAiDelta > 0)
                userUpdates.totalAiRequests = admin.firestore.FieldValue.increment(totalAiDelta);
            if (totalToolDelta > 0)
                userUpdates.totalToolExecutions = admin.firestore.FieldValue.increment(totalToolDelta);
            if (totalVoiceDelta > 0)
                userUpdates.totalVoiceSessions = admin.firestore.FieldValue.increment(totalVoiceDelta);
            transaction.set(userDocRef, userUpdates, { merge: true });
            // Update global aggregates
            transaction.set(overviewDocRef, {
                totalEvents: admin.firestore.FieldValue.increment(processed),
                totalAiRequests: admin.firestore.FieldValue.increment(totalAiDelta),
                successfulAiRequests: admin.firestore.FieldValue.increment(successAiDelta),
                failedAiRequests: admin.firestore.FieldValue.increment(failedAiDelta),
                totalToolExecutions: admin.firestore.FieldValue.increment(totalToolDelta),
                failedToolExecutions: admin.firestore.FieldValue.increment(failedToolDelta),
                totalVoiceSessions: admin.firestore.FieldValue.increment(totalVoiceDelta),
                lastUpdated: admin.firestore.FieldValue.serverTimestamp()
            }, { merge: true });
        }
        return { processed, rejected };
    });
}
async function processSessionHeartbeat(uid, data, db) {
    if (!validDocumentId(data?.sessionId)) {
        throw new https_1.HttpsError('invalid-argument', 'Missing or invalid sessionId');
    }
    const ref = db.collection('userSessions').doc(data.sessionId);
    await db.runTransaction(async (transaction) => {
        const snap = await transaction.get(ref);
        if (snap.exists && snap.get('userId') !== uid) {
            throw new https_1.HttpsError('permission-denied', 'Cannot update another user session');
        }
        if (snap.get('endedAt'))
            return;
        const now = admin.firestore.FieldValue.serverTimestamp();
        transaction.set(ref, {
            sessionId: data.sessionId, userId: uid, isActive: true, lastHeartbeatAt: now, updatedAt: now,
            ...(!snap.exists && { startedAt: now }),
            ...(data.appVersion && { appVersion: data.appVersion })
        }, { merge: true });
    });
}
async function processSessionEnd(uid, data, db) {
    if (!validDocumentId(data?.sessionId)) {
        throw new https_1.HttpsError('invalid-argument', 'Missing or invalid sessionId');
    }
    const ref = db.collection('userSessions').doc(data.sessionId);
    await db.runTransaction(async (transaction) => {
        const snap = await transaction.get(ref);
        if (!snap.exists)
            throw new https_1.HttpsError('not-found', 'Session does not exist');
        if (snap.get('userId') !== uid) {
            throw new https_1.HttpsError('permission-denied', 'Cannot end another user session');
        }
        if (snap.get('endedAt'))
            return;
        const start = snap.get('startedAt')?.toMillis();
        const now = admin.firestore.FieldValue.serverTimestamp();
        transaction.update(ref, {
            isActive: false, endedAt: now, updatedAt: now,
            ...(typeof start === 'number' && { durationMs: Math.max(0, Date.now() - start) })
        });
    });
}
//# sourceMappingURL=analytics.js.map