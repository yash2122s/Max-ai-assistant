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
exports.setAdminRole = exports.exportAdminReport = exports.getAdminErrors = exports.getAdminLiveActivity = exports.getAdminUserDetail = exports.getAdminUsers = exports.getAdminOverview = exports.sessionEnd = exports.sessionHeartbeat = exports.ingestActivityEvents = void 0;
const admin = __importStar(require("firebase-admin"));
const https_1 = require("firebase-functions/v2/https");
const analytics_1 = require("./analytics");
const admin_1 = require("./admin");
admin.initializeApp();
const db = admin.firestore();
// -----------------------------------------------------------------------------
// USER FACING / CLIENT CALLABLES
// -----------------------------------------------------------------------------
exports.ingestActivityEvents = (0, https_1.onCall)({ cors: true }, async (request) => {
    if (!request.auth) {
        throw new https_1.HttpsError('unauthenticated', 'User must be authenticated to send activity events.');
    }
    const uid = request.auth.uid;
    const { email, name } = request.auth.token;
    return await (0, analytics_1.processIngestEvents)(uid, request.data, db, { email, name });
});
exports.sessionHeartbeat = (0, https_1.onCall)({ cors: true }, async (request) => {
    if (!request.auth) {
        throw new https_1.HttpsError('unauthenticated', 'User must be authenticated.');
    }
    await (0, analytics_1.processSessionHeartbeat)(request.auth.uid, request.data, db);
    return { success: true };
});
exports.sessionEnd = (0, https_1.onCall)({ cors: true }, async (request) => {
    if (!request.auth) {
        throw new https_1.HttpsError('unauthenticated', 'User must be authenticated.');
    }
    await (0, analytics_1.processSessionEnd)(request.auth.uid, request.data, db);
    return { success: true };
});
// -----------------------------------------------------------------------------
// ADMIN CALLABLES (RESTRICTED VIA ADMIN CUSTOM CLAIM)
// -----------------------------------------------------------------------------
exports.getAdminOverview = (0, https_1.onCall)({ cors: true }, async (request) => {
    (0, admin_1.assertAdmin)(request.auth);
    return await (0, admin_1.getOverviewMetrics)(db);
});
exports.getAdminUsers = (0, https_1.onCall)({ cors: true }, async (request) => {
    (0, admin_1.assertAdmin)(request.auth);
    return await (0, admin_1.getUsersList)(db, request.data || {});
});
exports.getAdminUserDetail = (0, https_1.onCall)({ cors: true }, async (request) => {
    (0, admin_1.assertAdmin)(request.auth);
    return await (0, admin_1.getUserDetail)(db, request.data?.targetUid);
});
exports.getAdminLiveActivity = (0, https_1.onCall)({ cors: true }, async (request) => {
    (0, admin_1.assertAdmin)(request.auth);
    return await (0, admin_1.getLiveActivity)(db, request.data?.limit || 50);
});
exports.getAdminErrors = (0, https_1.onCall)({ cors: true }, async (request) => {
    (0, admin_1.assertAdmin)(request.auth);
    return await (0, admin_1.getErrorsAndPerformance)(db);
});
exports.exportAdminReport = (0, https_1.onCall)({ cors: true }, async (request) => {
    (0, admin_1.assertAdmin)(request.auth);
    const type = request.data?.type === 'users' ? 'users' : 'activity';
    const csv = await (0, admin_1.generateCsvExport)(db, type);
    return { csv, type, filename: `max-${type}-report-${Date.now()}.csv` };
});
exports.setAdminRole = (0, https_1.onCall)({ cors: true }, async (request) => {
    (0, admin_1.assertAdmin)(request.auth);
    const { targetUid, isAdmin } = request.data ?? {};
    if (!targetUid || typeof targetUid !== 'string') {
        throw new https_1.HttpsError('invalid-argument', 'Missing or invalid targetUid');
    }
    if (typeof isAdmin !== 'boolean') {
        throw new https_1.HttpsError('invalid-argument', 'isAdmin must be a boolean');
    }
    // Safety guard: Protect root admin from accidental lockout
    const ROOT_ADMIN_UID = 'RhBWlfWGKwNVnoLWu2iihHm4riG3';
    if (targetUid === ROOT_ADMIN_UID && !isAdmin) {
        throw new https_1.HttpsError('permission-denied', 'Cannot revoke privileges from primary root administrator.');
    }
    const auth = admin.auth();
    const account = await auth.getUser(targetUid);
    await auth.setCustomUserClaims(targetUid, { ...account.customClaims, admin: isAdmin });
    return { success: true, targetUid, isAdmin };
});
//# sourceMappingURL=index.js.map