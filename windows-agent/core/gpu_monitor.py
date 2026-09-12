"""
MAX AI — Direct NVML GPU Monitor (P1-5)
Direct, low-overhead NVML hardware telemetry access for NVIDIA GPUs,
eliminating repeated `nvidia-smi` subprocess forks with graceful fallback
for non-NVIDIA / headless environments.
"""

import ctypes
import logging
from typing import Dict, Any, Optional, List

logger = logging.getLogger("MAXWindowsAgent.GpuMonitor")


class NVMLGpuMonitor:
    """
    Provides direct NVML GPU telemetry without spawning subprocesses on every call:
    - Utilization %
    - VRAM used / total / free (MB)
    - Temperature (°C)
    - Power draw (W)
    - GPU name & Driver version
    """

    def __init__(self):
        self._is_nvml_available: bool = False
        self._nvml_initialized: bool = False
        self._pynvml = None
        self._nvml_dll = None
        self._device_count: int = 0
        self._init_nvml()

    def _init_nvml(self):
        """Attempts to dynamically load NVML without raising exceptions on non-NVIDIA hardware."""
        # 1. Try pynvml library if installed
        try:
            import pynvml
            pynvml.nvmlInit()
            self._pynvml = pynvml
            self._device_count = pynvml.nvmlDeviceGetCount()
            self._nvml_initialized = True
            self._is_nvml_available = True
            logger.info(f"🚀 Direct NVML GPU Monitor initialized ({self._device_count} NVIDIA GPU(s) found)")
            return
        except Exception as e:
            logger.debug(f"pynvml initialization notice: {e}")

        # 2. Try loading Windows nvml.dll via ctypes
        try:
            # Standard Windows NVML DLL path
            nvml_paths = [
                "nvml.dll",
                r"C:\Windows\System32\nvml.dll",
                r"C:\Program Files\NVIDIA Corporation\NVSMI\nvml.dll"
            ]
            for p in nvml_paths:
                try:
                    self._nvml_dll = ctypes.CDLL(p)
                    # nvmlInit_v2 = 0 on success
                    res = self._nvml_dll.nvmlInit_v2()
                    if res == 0:
                        device_count = ctypes.c_uint()
                        self._nvml_dll.nvmlDeviceGetCount_v2(ctypes.byref(device_count))
                        self._device_count = device_count.value
                        self._nvml_initialized = True
                        self._is_nvml_available = True
                        logger.info(f"🚀 Direct NVML DLL initialized ({self._device_count} NVIDIA GPU(s) found)")
                        return
                except Exception:
                    continue
        except Exception as ex:
            logger.debug(f"Direct NVML ctypes initialization notice: {ex}")

        # NVML not available (non-NVIDIA or driver not installed)
        self._is_nvml_available = False
        self._nvml_initialized = False
        logger.debug("Direct NVML not available on this system (using graceful non-NVIDIA fallback).")

    @property
    def is_available(self) -> bool:
        return self._is_nvml_available and self._device_count > 0

    def get_gpu_metrics(self, device_index: int = 0) -> Dict[str, Any]:
        """
        Retrieves real-time GPU telemetry directly from NVML.
        Returns a normalized dictionary.
        """
        if not self.is_available:
            return {
                "status": "unavailable",
                "available": False,
                "message": "NVIDIA NVML GPU telemetry is not available on this hardware."
            }

        try:
            if self._pynvml:
                handle = self._pynvml.nvmlDeviceGetHandleByIndex(device_index)
                name = self._pynvml.nvmlDeviceGetName(handle)
                if isinstance(name, bytes):
                    name = name.decode("utf-8")

                mem_info = self._pynvml.nvmlDeviceGetMemoryInfo(handle)
                util_rates = self._pynvml.nvmlDeviceGetUtilizationRates(handle)
                temp = self._pynvml.nvmlDeviceGetTemperature(handle, self._pynvml.NVML_TEMPERATURE_GPU)

                try:
                    power_mw = self._pynvml.nvmlDeviceGetPowerUsage(handle)
                    power_w = round(power_mw / 1000.0, 1)
                except Exception:
                    power_w = None

                try:
                    driver_ver = self._pynvml.nvmlSystemGetDriverVersion()
                    if isinstance(driver_ver, bytes):
                        driver_ver = driver_ver.decode("utf-8")
                except Exception:
                    driver_ver = "Unknown"

                return {
                    "status": "success",
                    "available": True,
                    "provider": "NVML_Direct",
                    "device_index": device_index,
                    "gpu_name": name,
                    "driver_version": driver_ver,
                    "gpu_utilization_percent": util_rates.gpu,
                    "memory_utilization_percent": util_rates.memory,
                    "vram_used_mb": round(mem_info.used / (1024 * 1024), 1),
                    "vram_total_mb": round(mem_info.total / (1024 * 1024), 1),
                    "vram_free_mb": round(mem_info.free / (1024 * 1024), 1),
                    "temperature_c": temp,
                    "power_watts": power_w
                }

            return {
                "status": "unavailable",
                "available": False,
                "message": "NVML handle active but provider parser uninitialized."
            }

        except Exception as e:
            logger.error(f"Error reading direct NVML metrics: {e}")
            return {
                "status": "error",
                "available": False,
                "message": str(e)
            }

    def shutdown(self):
        """Cleanly releases NVML resources."""
        if self._nvml_initialized:
            try:
                if self._pynvml:
                    self._pynvml.nvmlShutdown()
                elif self._nvml_dll:
                    self._nvml_dll.nvmlShutdown()
            except Exception as e:
                logger.debug(f"NVML shutdown notice: {e}")
            finally:
                self._nvml_initialized = False


# Global singleton instance
gpu_monitor = NVMLGpuMonitor()
