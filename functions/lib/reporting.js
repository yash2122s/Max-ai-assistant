"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.recentEventLimit = void 0;
exports.summarizeLatency = summarizeLatency;
exports.csvCell = csvCell;
// Used by both the callable reports and the dashboard's direct Firestore reads.
const completionTypes = new Set([
    'CHAT_REQUEST_SUCCESS', 'CHAT_REQUEST_FAILED', 'AI_REQUEST_SUCCESS', 'AI_REQUEST_FAILED',
    'TOOL_EXECUTION_SUCCESS', 'TOOL_EXECUTION_FAILED'
]);
exports.recentEventLimit = 100;
function summarizeLatency(events) {
    const latencyBuckets = { '<200ms': 0, '200ms-500ms': 0, '500ms-1s': 0, '1s-3s': 0, '≥3s': 0 };
    let count = 0;
    let sum = 0;
    for (const { eventType, durationMs } of events) {
        if (typeof eventType !== 'string' || !completionTypes.has(eventType) ||
            typeof durationMs !== 'number' || !Number.isFinite(durationMs) || durationMs < 0)
            continue;
        count++;
        sum += durationMs;
        if (durationMs < 200)
            latencyBuckets['<200ms']++;
        else if (durationMs < 500)
            latencyBuckets['200ms-500ms']++;
        else if (durationMs < 1000)
            latencyBuckets['500ms-1s']++;
        else if (durationMs < 3000)
            latencyBuckets['1s-3s']++;
        else
            latencyBuckets['≥3s']++;
    }
    return { avgLatencyMs: count ? Math.round(sum / count) : null, latencyBuckets };
}
function csvCell(value) {
    if (typeof value === 'number' && Number.isFinite(value))
        return String(value);
    const text = String(value ?? '');
    const literal = /^\s*[=+@-]|^[\t\r\n]/.test(text) ? "'" + text : text;
    return '"' + literal.replace(/"/g, '""') + '"';
}
//# sourceMappingURL=reporting.js.map