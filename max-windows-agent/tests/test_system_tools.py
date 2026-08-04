import unittest
import asyncio
import os
import sys

# Ensure agent directory is in path
sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from tools.clipboard_tool import clipboard_helper
from tools.file_tool import file_helper
from tools.system_tool import system_helper
from tools.terminal_tool import terminal_helper
from tools.windows_agent_tool import WindowsAgentTool

class TestSystemTools(unittest.TestCase):
    def test_clipboard_set_get(self):
        # Write a test string to Windows clipboard
        test_val = "MAX_Ecosystem_Test_String_123"
        success = clipboard_helper.set_text(test_val)
        self.assertTrue(success)
        
        # Read back and compare
        read_val = clipboard_helper.get_text()
        self.assertEqual(read_val, test_val)

    def test_path_sandboxing(self):
        user_profile = os.path.abspath(os.path.expanduser("~"))
        
        # Safe paths
        safe_path = os.path.join(user_profile, "Downloads", "test.txt")
        self.assertTrue(file_helper.is_path_safe(safe_path))
        
        # Unsafe traversal paths
        unsafe_path = "C:\\Windows\\System32\\cmd.exe"
        self.assertFalse(file_helper.is_path_safe(unsafe_path))
        
        unsafe_relative = os.path.join(user_profile, "..", "..", "Windows", "System32")
        self.assertFalse(file_helper.is_path_safe(unsafe_relative))

    def test_window_list_and_fuzzy_find(self):
        # Enumerating windows should succeed without exceptions
        windows = system_helper.list_windows()
        self.assertIsInstance(windows, list)
        
        # Fetch active window
        active = system_helper.get_active_window()
        self.assertIn("title", active)
        self.assertIn("process_name", active)
        self.assertIn("hwnd", active)

    def test_screenshot_generation(self):
        # Capture screenshot with high scale compression (0.1) for testing speed
        res = system_helper.capture_screenshot(quality=10, scale=0.1)
        self.assertIn("mime_type", res)
        self.assertIn("base64_data", res)
        self.assertIn("metadata", res)
        
        metadata = res["metadata"]
        self.assertIn("resolution", metadata)
        self.assertIn("active_window", metadata)
        self.assertIn("screen_count", metadata)

    def test_file_search_safeguard(self):
        # Search path outside sandbox should throw PermissionError
        with self.assertRaises(PermissionError):
            file_helper.search_files("cmd.exe", "C:\\Windows")

    def test_challenge_response_authentication(self):
        from core.session_manager import session_manager
        import hmac
        import hashlib
        
        device_id = "test-device-id-123"
        pair_token = "secret-token-abc"
        
        # Inject paired device mock
        session_manager.paired_devices.append({
            "device_id": device_id,
            "device_name": "Test Phone",
            "token": pair_token
        })
        
        try:
            # Generate challenge
            challenge = session_manager.generate_auth_challenge(device_id)
            self.assertEqual(len(challenge), 64)
            
            # Compute valid signature
            sig = hmac.new(
                pair_token.encode("utf-8"),
                challenge.encode("utf-8"),
                hashlib.sha256
            ).hexdigest()
            
            # Verify signature (must succeed)
            verified = session_manager.verify_auth_response(device_id, sig)
            self.assertTrue(verified)
        finally:
            # Clean up injected mock
            session_manager.paired_devices = [d for d in session_manager.paired_devices if d.get("device_id") != device_id]

    def test_challenge_expiry_and_replay(self):
        from core.session_manager import session_manager
        import time
        import hmac
        import hashlib
        
        device_id = "test-device-id-expiry"
        pair_token = "secret-token-expiry"
        
        session_manager.paired_devices.append({
            "device_id": device_id,
            "device_name": "Test Phone",
            "token": pair_token
        })
        
        try:
            # Test challenge expiry (manually setting timestamp back 6 seconds)
            challenge = session_manager.generate_auth_challenge(device_id)
            session_manager.active_challenges[device_id]["timestamp"] = time.time() - 6.0
            
            sig = hmac.new(
                pair_token.encode("utf-8"),
                challenge.encode("utf-8"),
                hashlib.sha256
            ).hexdigest()
            
            # Verification should fail due to TTL expiry
            verified = session_manager.verify_auth_response(device_id, sig)
            self.assertFalse(verified)
            
            # Test replay protection: verified once must consume challenge
            challenge2 = session_manager.generate_auth_challenge(device_id)
            sig2 = hmac.new(
                pair_token.encode("utf-8"),
                challenge2.encode("utf-8"),
                hashlib.sha256
            ).hexdigest()
            
            # First verification succeeds
            verified_first = session_manager.verify_auth_response(device_id, sig2)
            self.assertTrue(verified_first)
            
            # Replaying the same verification must fail (challenge is invalidated)
            verified_second = session_manager.verify_auth_response(device_id, sig2)
            self.assertFalse(verified_second)
            
        finally:
            session_manager.paired_devices = [d for d in session_manager.paired_devices if d.get("device_id") != device_id]

    def test_app_indexer(self):
        from tools.app_tool import app_helper
        apps = app_helper.list_installed_apps()
        self.assertIsInstance(apps, list)
        
        running_check = app_helper.is_running("explorer")
        self.assertIn("is_running", running_check)


class TestTerminalAsync(unittest.IsolatedAsyncioTestCase):
    async def test_terminal_async_run_and_cancel(self):
        # Run a simple echo command
        async def mock_progress(msg):
            pass
            
        tool = WindowsAgentTool()
        result = await tool.execute(
            "core.terminal:run",
            {"command": "echo Hello_MAX"},
            mock_progress
        )
        self.assertEqual(result.get("status"), "success")
        
        # Test command cancellation
        # We start a sleeping process and cancel it
        task = asyncio.create_task(
            terminal_helper.run_command(
                "powershell -NoProfile -Command Start-Sleep -Seconds 10",
                "test_cancel_uuid",
                mock_progress
            )
        )
        
        # Let it yield execution control to start the process
        await asyncio.sleep(0.5)
        
        # Send cancellation
        cancelled = terminal_helper.cancel_task("test_cancel_uuid")
        self.assertTrue(cancelled)
        
        # Wait for the task to exit
        res = await task
        self.assertEqual(res.get("status"), "failed")
        self.assertEqual(res.get("error", {}).get("code"), "TIMEOUT")

if __name__ == "__main__":
    unittest.main()
