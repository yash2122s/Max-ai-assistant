# Phase 1A Exit Test Checklist

This checklist represents the formal exit criteria for **Phase 1A**. It must be executed before starting Phase 1B refactoring and run as a regression suite before any future release.

---

## 1. Functional Tests

- [ ] **Agent Startup**: Server starts cleanly, reads `storage/config.json`, prints pairing code, and listens on port `9000`.
- [ ] **Client Pairing**: Client connects, triggers `hello` handshake, enters 6-digit passcode, and server returns a valid session token.
- [ ] **Token Persistence**: Token is cached locally by client; subsequent connections authenticate automatically using the token.
- [ ] **Command Execution - `dir`**: Executing `dir` on workspace or absolute directory returns correct file list.
- [ ] **Command Execution - `cd`**: Executing `cd` successfully updates the persistent working directory.
- [ ] **Command Execution - `where`**: Running `where` returns the correct local path for specified binaries.

---

## 2. Robustness & Error Testing

- [ ] **Invalid Pairing Code**: Submitting incorrect 6-digit code returns `AUTH_FAILED` error.
- [ ] **Invalid Token**: Submitting tool requests with wrong or spoofed token returns `INVALID_TOKEN` error.
- [ ] **Tool Timeout**: Simulating tool exceeding timing thresholds returns `TOOL_TIMEOUT` error.
- [ ] **Unsupported Tool**: Requesting a non-registered tool returns `UNSUPPORTED_TOOL` error.
- [ ] **Malformed JSON**: Sending invalid JSON structure returns `INVALID_PACKET` error.

---

## 3. Reliability & Reconnection

- [ ] **Heartbeats**: Check active ping/pongs maintain connection with a 25s frequency over long idle periods.
- [ ] **Android Client Restart**: Killing/relaunching Android app automatically reconnects and re-authenticates token.
- [ ] **Windows Agent Restart**: Terminating/relaunching python server allows clients to reconnect and re-authenticate without code re-pairing.
- [ ] **Network Interruptions**: Toggling Wi-Fi offline and online triggers client reconnect retry loop and restores connection.
- [ ] **Concurrence Stress Test**: Execute 100 sequential CMD requests and ensure 0% packet loss and no out-of-order execution.

---

## 4. Diagnostics & Resources

- [ ] **Log Verification**: Confirm all system transitions (`Agent Started`, `Client Connected`, `Hello`, `Pair Success`, `Tool Request`, `Tool Response`, `Client Disconnected`, `Reconnect`) are written correctly into `storage/logs/max-agent.log`.
- [ ] **Uptime & Memory Leak Check**: Run agent continuously for 1 hour; verify memory footprint remains stable.
- [ ] **CPU Usage Check**: Verify CPU utilization stays near 0% during idle socket states.

---

## Sign-off

**Final Result**: [PASS / FAIL]  
**Tester**:  
**Date**:  
