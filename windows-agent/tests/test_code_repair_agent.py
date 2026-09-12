"""
MAX AI — Unit Tests for CodeRepairAgent (P0-3 Self-Repairing Code Agent)
Tests:
1. Traceback parsing (file, line number, exception, message)
2. PathSandbox allows workspace files and blocks traversal / sensitive paths
3. PatchValidator enforces Python syntax validity via ast.parse
4. PatchValidator rejects prohibited security tokens (eval, exec)
5. Backup creation and rollback functionality
6. Targeted test runner execution & command allowlist
7. Successful repair within bounded attempts
8. Termination and rollback after max attempts exhausted
"""

import os
import shutil
import tempfile
import unittest
import asyncio

from core.code_repair_agent import (
    TracebackParser,
    PathSandbox,
    PatchValidator,
    CodeRepairAgent
)


class TestCodeRepairAgent(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.sandbox = PathSandbox(allowed_roots=[self.temp_dir])
        self.agent = CodeRepairAgent(max_attempts=3, sandbox=self.sandbox)

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_01_traceback_parser(self):
        """Test 1: Structured extraction of error details from raw traceback."""
        tb_sample = """
Traceback (most recent call last):
  File "C:\\Users\\yaswa\\Downloads\\gemini-live\\windows-agent\\tools\\example.py", line 42, in calculate_metric
    result = value / divisor
ZeroDivisionError: division by zero
"""
        info = TracebackParser.parse(tb_sample)
        self.assertIsNotNone(info)
        self.assertEqual(info.file_path, "C:\\Users\\yaswa\\Downloads\\gemini-live\\windows-agent\\tools\\example.py")
        self.assertEqual(info.line_number, 42)
        self.assertEqual(info.exception_type, "ZeroDivisionError")
        self.assertEqual(info.error_message, "division by zero")

    def test_02_path_sandbox_boundaries(self):
        """Test 2: PathSandbox permits allowed workspace files and blocks violations."""
        safe_file = os.path.join(self.temp_dir, "script.py")
        is_safe, _ = self.sandbox.is_safe_path(safe_file)
        self.assertTrue(is_safe)

        # Path traversal outside workspace
        traversal_file = os.path.join(self.temp_dir, "..", "windows_system.dll")
        is_safe, msg = self.sandbox.is_safe_path(traversal_file)
        self.assertFalse(is_safe)

        # Protected file (.env)
        env_file = os.path.join(self.temp_dir, ".env")
        is_safe, msg = self.sandbox.is_safe_path(env_file)
        self.assertFalse(is_safe)
        self.assertIn("blocked", msg.lower())

    def test_03_patch_syntax_validation(self):
        """Test 3: PatchValidator accepts valid Python AST and rejects syntax errors."""
        valid_code = "def add(a, b):\n    return a + b\n"
        is_valid, err = PatchValidator.validate_code_syntax(valid_code)
        self.assertTrue(is_valid)
        self.assertIsNone(err)

        invalid_code = "def broken(:\n    return\n"
        is_valid, err = PatchValidator.validate_code_syntax(invalid_code)
        self.assertFalse(is_valid)
        self.assertIn("SyntaxError", err)

    def test_04_prohibited_security_tokens(self):
        """Test 4: Prohibits eval, exec, and arbitrary execution primitives."""
        eval_code = "def run_dynamic(x):\n    return eval(x)\n"
        is_valid, err = PatchValidator.validate_security_rules(eval_code)
        self.assertFalse(is_valid)
        self.assertIn("eval(", err)

        exec_code = "exec('import os; os.system(\"calc\")')"
        is_valid, err = PatchValidator.validate_security_rules(exec_code)
        self.assertFalse(is_valid)

    def test_05_backup_and_rollback(self):
        """Test 5: Safe patching creates .bak backup and supports clean rollback."""
        target_file = os.path.join(self.temp_dir, "module.py")
        with open(target_file, "w") as f:
            f.write("ORIGINAL_CONTENT = 1\n")

        # Apply patch
        res = self.agent.apply_patch_safely(target_file, "MODIFIED_CONTENT = 2\n")
        self.assertEqual(res["status"], "success")

        with open(target_file, "r") as f:
            self.assertEqual(f.read(), "MODIFIED_CONTENT = 2\n")

        # Rollback
        rolled_back = self.agent.rollback_patch(target_file)
        self.assertTrue(rolled_back)

        with open(target_file, "r") as f:
            self.assertEqual(f.read(), "ORIGINAL_CONTENT = 1\n")

    def test_06_test_runner_command_allowlist(self):
        """Test 6: Only pytest/unittest commands are permitted in test runner."""
        allowed_res = self.agent.run_targeted_test("py -m unittest --help")
        self.assertEqual(allowed_res["status"], "success")

        disallowed_res = self.agent.run_targeted_test("rmdir /s /q test_dir")
        self.assertEqual(disallowed_res["status"], "error")
        self.assertEqual(disallowed_res["error_code"], "TEST_COMMAND_DISALLOWED")

    def test_07_repair_loop_success(self):
        """Test 7: Bounded repair loop succeeds when proposal passes validation."""
        target_file = os.path.join(self.temp_dir, "math_utils.py")
        with open(target_file, "w") as f:
            f.write("def divide(a, b):\n    return a / b\n")

        tb = f"""
Traceback (most recent call last):
  File "{target_file}", line 2, in divide
    return a / b
ZeroDivisionError: division by zero
"""
        def mock_proposer(tb_info, raw_code):
            return "def divide(a, b):\n    if b == 0:\n        return 0\n    return a / b\n"

        async def run():
            res = await self.agent.execute_repair_loop(tb, mock_proposer)
            self.assertEqual(res["status"], "success")
            self.assertEqual(res["attempts_used"], 1)

            with open(target_file, "r") as f:
                content = f.read()
                self.assertIn("if b == 0:", content)

        asyncio.run(run())

    def test_08_repair_loop_exhaustion(self):
        """Test 8: Max attempts exhausted returns controlled error without infinite loop."""
        target_file = os.path.join(self.temp_dir, "broken.py")
        with open(target_file, "w") as f:
            f.write("def broken():\n    pass\n")

        tb = f"""
Traceback (most recent call last):
  File "{target_file}", line 2, in broken
    raise ValueError("Persistently broken")
ValueError: Persistently broken
"""
        def bad_proposer(tb_info, raw_code):
            # Proposes invalid syntax to trigger failure
            return "def broken(:"

        async def run():
            res = await self.agent.execute_repair_loop(tb, bad_proposer)
            self.assertEqual(res["status"], "error")
            self.assertEqual(res["error_code"], "MAX_ATTEMPTS_EXHAUSTED")
            self.assertEqual(len(res["history"]), 3)

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()
