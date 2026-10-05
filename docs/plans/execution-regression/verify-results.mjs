import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

// Measures generated JUnit reports; does not replace or silently rerun Gradle.
const root = process.cwd();
const directory = path.join(root, 'app/build/test-results/testDebugUnitTest');
const suites = fs.readdirSync(directory).filter(name => name.startsWith('TEST-') && name.endsWith('.xml')).map(file => {
  const xml = fs.readFileSync(path.join(directory, file), 'utf8');
  const header = xml.match(/<testsuite\b([^>]*)>/);
  assert(header, `Missing suite header: ${file}`);
  const attributes = Object.fromEntries([...header[1].matchAll(/(\w+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
  return { name: attributes.name, tests: Number(attributes.tests), failures: Number(attributes.failures),
    errors: Number(attributes.errors), skipped: Number(attributes.skipped), timestamp: attributes.timestamp, xml };
});
assert(suites.length > 0, 'No JUnit reports');
const sum = items => items.reduce((out, suite) => ({ tests: out.tests + suite.tests,
  failures: out.failures + suite.failures, errors: out.errors + suite.errors, skipped: out.skipped + suite.skipped }),
  { tests: 0, failures: 0, errors: 0, skipped: 0 });
console.log('FULL_SUITE', JSON.stringify(sum(suites)));
for (const suite of suites.filter(s => s.failures || s.errors)) {
  console.log('FAILING_SUITE', suite.name, suite.failures, suite.errors);
}
const required = ['BasicExecutionRegressionTest', 'LongHorizonTaskExecutionTest', 'OpenAppVerificationUnitTest',
  'WaitActionUnitTest', 'CallExecutionTruthUnitTest', 'WhatsAppAutomationIntegrationTest', 'ExecutionTruthUnitTest',
  'TaskScopedApprovalTest', 'TaskTransitionAuthorityTest', 'AgentPersistenceRegressionTest',
  'LiveProtocolRegressionTest', 'VoiceExecutionConvergenceTest'];
for (const name of required) assert(suites.some(s => s.name.endsWith(`.${name}`)), `Missing required suite ${name}`);
const relevant = suites.filter(s => /^com\.example\.(automation|network|voice|domain)\./.test(s.name) &&
  s.name !== 'com.example.automation.agent.UiAgentModelTest');
console.log('RELEVANT_SUITE', JSON.stringify(sum(relevant)));
for (const suite of suites.filter(s => required.some(name => s.name.endsWith(`.${name}`)))) {
  console.log('REQUIRED_SUITE', suite.name, JSON.stringify(sum([suite])));
}
const traces = suites.flatMap(s => [...s.xml.matchAll(/JVM_EXECUTION_TRACE (\{[^\r\n]+\})/g)].map(m => JSON.parse(m[1])));
assert(traces.length === 4, `Expected four command traces, got ${traces.length}`);
for (const trace of traces) {
  assert(trace.executorSuccess && trace.finalTaskState === 'COMPLETED', `Nonterminal command trace: ${trace.command}`);
  assert(trace.taskId && trace.stepId && trace.executionId && trace.verificationResult, 'Trace lacks identity/evidence');
  console.log('COMMAND_TRACE', JSON.stringify(trace));
}
if (process.argv.includes('--relevant')) {
  assert.equal(sum(relevant).failures + sum(relevant).errors, 0, 'Relevant test failures remain');
  console.log('RELEVANT_EXECUTION_REGRESSIONS_PASS');
}
if (process.argv.includes('--full')) {
  assert.equal(sum(suites).failures + sum(suites).errors, 0, 'Full suite failures remain');
  console.log('FULL_APP_SUITE_PASS');
}
