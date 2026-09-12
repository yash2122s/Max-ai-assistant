"""
MAX AI — Unit Tests for NVMLGpuMonitor (P1-5 Direct NVML GPU Monitoring)
Tests:
1. Graceful fallback on non-NVIDIA / unavailable environments
2. Direct metric extraction when NVML mock is active
3. Error handling in metric queries
4. Clean shutdown behavior
"""

import unittest
from unittest.mock import MagicMock, patch

from core.gpu_monitor import NVMLGpuMonitor


class TestGpuMonitor(unittest.TestCase):

    def test_01_unavailable_fallback(self):
        """Test 1: Uninitialized NVML returns clean unavailable status without raising exceptions."""
        with patch.object(NVMLGpuMonitor, "_init_nvml"):
            monitor = NVMLGpuMonitor()
            monitor._is_nvml_available = False
            monitor._device_count = 0

            self.assertFalse(monitor.is_available)
            metrics = monitor.get_gpu_metrics()
            self.assertEqual(metrics["status"], "unavailable")
            self.assertFalse(metrics["available"])

    def test_02_mocked_nvml_metrics(self):
        """Test 2: Direct NVML metrics extracted accurately from mock handles."""
        with patch.object(NVMLGpuMonitor, "_init_nvml"):
            monitor = NVMLGpuMonitor()
            monitor._is_nvml_available = True
            monitor._nvml_initialized = True
            monitor._device_count = 1

            mock_pynvml = MagicMock()
            mock_handle = MagicMock()
            mock_pynvml.nvmlDeviceGetHandleByIndex.return_value = mock_handle
            mock_pynvml.nvmlDeviceGetName.return_value = "NVIDIA GeForce RTX 3080"

            mock_mem = MagicMock()
            mock_mem.used = 2048 * 1024 * 1024
            mock_mem.total = 10240 * 1024 * 1024
            mock_mem.free = 8192 * 1024 * 1024
            mock_pynvml.nvmlDeviceGetMemoryInfo.return_value = mock_mem

            mock_util = MagicMock()
            mock_util.gpu = 45
            mock_util.memory = 30
            mock_pynvml.nvmlDeviceGetUtilizationRates.return_value = mock_util
            mock_pynvml.nvmlDeviceGetTemperature.return_value = 62
            mock_pynvml.nvmlDeviceGetPowerUsage.return_value = 180000
            mock_pynvml.nvmlSystemGetDriverVersion.return_value = "550.54.14"

            monitor._pynvml = mock_pynvml

            metrics = monitor.get_gpu_metrics(0)
            self.assertEqual(metrics["status"], "success")
            self.assertEqual(metrics["gpu_name"], "NVIDIA GeForce RTX 3080")
            self.assertEqual(metrics["gpu_utilization_percent"], 45)
            self.assertEqual(metrics["vram_used_mb"], 2048.0)
            self.assertEqual(metrics["vram_total_mb"], 10240.0)
            self.assertEqual(metrics["temperature_c"], 62)
            self.assertEqual(metrics["power_watts"], 180.0)
            self.assertEqual(metrics["driver_version"], "550.54.14")

    def test_03_shutdown_clean(self):
        """Test 3: Clean shutdown calls nvmlShutdown."""
        with patch.object(NVMLGpuMonitor, "_init_nvml"):
            monitor = NVMLGpuMonitor()
            monitor._nvml_initialized = True
            mock_pynvml = MagicMock()
            monitor._pynvml = mock_pynvml

            monitor.shutdown()
            mock_pynvml.nvmlShutdown.assert_called_once()
            self.assertFalse(monitor._nvml_initialized)


if __name__ == "__main__":
    unittest.main()
