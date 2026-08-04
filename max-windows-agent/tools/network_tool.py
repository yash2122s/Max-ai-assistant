import logging
import subprocess
import json
import socket
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.Network")


class NetworkHelper:
    def _run_ps(self, script: str, timeout: int = 10):
        return subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True, timeout=timeout)

    def get_interfaces(self) -> list:
        try:
            res = self._run_ps('Get-CimInstance Win32_NetworkAdapter -Filter \'NetEnabled=True\'|Select-Object Name,Description,MACAddress,Speed,AdapterType,NetConnectionID,NetEnabled,Manufacturer|ConvertTo-Json -Compress')
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                for nic in data:
                    if nic.get("Speed"):
                        speed = nic["Speed"]
                        if speed >= 1_000_000_000:
                            nic["SpeedDisplay"] = f"{speed / 1_000_000_000:.0f} Gbps"
                        elif speed >= 1_000_000:
                            nic["SpeedDisplay"] = f"{speed / 1_000_000:.0f} Mbps"
                        else:
                            nic["SpeedDisplay"] = f"{speed // 1000} Kbps"
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to get network interfaces: {e}")
            return []

    def get_ip_config(self) -> list:
        try:
            res = self._run_ps('Get-CimInstance Win32_NetworkAdapterConfiguration -Filter \'IPEnabled=True\'|Select-Object Description,Index,@{N=\'IPAddress\';E={$_.IPAddress -join \', \'}},@{N=\'Subnet\';E={$_.IPSubnet -join \', \'}},@{N=\'DefaultGateway\';E={$_.DefaultIPGateway -join \', \'}},@{N=\'DNSServers\';E={$_.DNSServerSearchOrder -join \', \'}},DHCPEnabled,DHCPServer,MACAddress|ConvertTo-Json -Compress')
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to get IP config: {e}")
            return []

    def check_connectivity(self, host: str = "8.8.8.8", port: int = 80, timeout: int = 5) -> dict:
        results = {"host": host, "port": port}
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(timeout)
            result = s.connect_ex((host, port))
            s.close()
            results["internet"] = result == 0
            results["latency_ms"] = None
        except Exception as e:
            results["internet"] = False
            results["error"] = str(e)

        if results.get("internet"):
            try:
                import time
                start = time.time()
                import urllib.request
                urllib.request.urlopen(f"http://{host}", timeout=timeout)
                results["latency_ms"] = round((time.time() - start) * 1000, 1)
            except Exception:
                pass

        try:
            results["local_ip"] = socket.gethostbyname(socket.gethostname())
        except Exception:
            results["local_ip"] = "unknown"

        return results

    def get_network_usage(self) -> dict:
        try:
            res = self._run_ps('$interfaces=Get-CimInstance Win32_PerfFormattedData_Tcpip_NetworkInterface;$interfaces|Select-Object Name,@{N=\'BytesReceivedMB\';E={[math]::Round($_.BytesReceivedPerSec/1MB,2)}},@{N=\'BytesSentMB\';E={[math]::Round($_.BytesSentPerSec/1MB,2)}},@{N=\'BytesTotalMB\';E={[math]::Round(($_.BytesReceivedPerSec+$_.BytesSentPerSec)/1MB,2)}},CurrentBandwidth,PacketsReceivedPerSec,PacketsSentPerSec|ConvertTo-Json -Compress')
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return {"interfaces": data}
            return {"interfaces": []}
        except Exception as e:
            logger.error(f"Failed to get network usage: {e}")
            return {"interfaces": []}

    def scan_ports(self, host: str = "127.0.0.1", ports: str = "80,443,8080") -> dict:
        try:
            port_list = [int(p.strip()) for p in ports.split(",") if p.strip().isdigit()]
            open_ports = []
            for port in port_list:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.settimeout(2)
                result = s.connect_ex((host, port))
                if result == 0:
                    try:
                        service = socket.getservbyport(port)
                    except Exception:
                        service = "unknown"
                    open_ports.append({"port": port, "service": service})
                s.close()
            return {"host": host, "open_ports": open_ports, "scanned": len(port_list)}
        except Exception as e:
            return {"error": str(e)}

    def get_wifi_profiles(self) -> list:
        try:
            res = subprocess.run(["netsh", "wlan", "show", "profiles"], capture_output=True, text=True, timeout=10)
            profiles = []
            for line in res.stdout.splitlines():
                if "All User Profile" in line:
                    profile_name = line.split(":")[1].strip()
                    profiles.append({"ssid": profile_name})
            return profiles
        except Exception as e:
            logger.error(f"Failed to get WiFi profiles: {e}")
            return []

    def get_active_wifi(self) -> dict:
        try:
            res = subprocess.run(["netsh", "wlan", "show", "interfaces"], capture_output=True, text=True, timeout=10)
            result = {}
            for line in res.stdout.splitlines():
                if "SSID" in line:
                    parts = line.split(":")
                    if len(parts) > 1:
                        result["ssid"] = parts[1].strip()
                elif "State" in line:
                    parts = line.split(":")
                    if len(parts) > 1:
                        result["state"] = parts[1].strip()
                elif "Signal" in line:
                    parts = line.split(":")
                    if len(parts) > 1:
                        result["signal"] = parts[1].strip()
                elif "Radio type" in line:
                    parts = line.split(":")
                    if len(parts) > 1:
                        result["radio_type"] = parts[1].strip()
                elif "BSSID" in line:
                    parts = line.split(":")
                    if len(parts) > 1:
                        result["bssid"] = parts[1].strip()
                elif "Channel" in line:
                    parts = line.split(":")
                    if len(parts) > 1:
                        result["channel"] = parts[1].strip()
            return result
        except Exception as e:
            logger.error(f"Failed to get active WiFi: {e}")
            return {}

    def flush_dns(self) -> dict:
        try:
            subprocess.run(["ipconfig", "/flushdns"], capture_output=True, timeout=10)
            return {"status": "success", "message": "DNS cache flushed"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def renew_ip(self) -> dict:
        try:
            subprocess.run(["ipconfig", "/release"], capture_output=True, timeout=10)
            subprocess.run(["ipconfig", "/renew"], capture_output=True, timeout=30)
            return {"status": "success", "message": "IP address renewed"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def traceroute(self, host: str = "8.8.8.8") -> dict:
        try:
            res = subprocess.run(["tracert", "-h", "15", host], capture_output=True, text=True, timeout=30)
            hops = [line.strip() for line in res.stdout.splitlines() if "ms" in line and "<1" not in line]
            return {"host": host, "hops": hops[:15], "raw": res.stdout}
        except Exception as e:
            return {"host": host, "error": str(e)}

    def ping(self, host: str = "8.8.8.8", count: int = 4) -> dict:
        try:
            res = subprocess.run(["ping", host, "-n", str(count)], capture_output=True, text=True, timeout=15)
            return {"host": host, "output": res.stdout, "success": res.returncode == 0}
        except Exception as e:
            return {"host": host, "error": str(e)}


network_helper = NetworkHelper()


class NetworkTool(BaseTool):
    @property
    def name(self) -> str:
        return "network"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running network/{action}...")
        try:
            if action == "interfaces":
                return {"status": "success", "output": network_helper.get_interfaces()}
            elif action == "ipconfig":
                return {"status": "success", "output": network_helper.get_ip_config()}
            elif action == "connectivity":
                host = arguments.get("host", "8.8.8.8")
                port = int(arguments.get("port", 80))
                timeout = int(arguments.get("timeout", 5))
                return {"status": "success", "output": network_helper.check_connectivity(host, port, timeout)}
            elif action == "ping":
                host = arguments.get("host", "8.8.8.8")
                count = int(arguments.get("count", 4))
                return {"status": "success", "output": network_helper.ping(host, count)}
            elif action == "usage":
                return {"status": "success", "output": network_helper.get_network_usage()}
            elif action == "ports":
                host = arguments.get("host", "127.0.0.1")
                ports = arguments.get("ports", "80,443,8080")
                return {"status": "success", "output": network_helper.scan_ports(host, ports)}
            elif action == "wifi_profiles":
                return {"status": "success", "output": network_helper.get_wifi_profiles()}
            elif action == "active_wifi":
                return {"status": "success", "output": network_helper.get_active_wifi()}
            elif action == "wifi_status":
                profiles = network_helper.get_wifi_profiles()
                active = network_helper.get_active_wifi()
                return {"status": "success", "output": {"active": active, "saved_profiles": profiles}}
            elif action == "flush_dns":
                return network_helper.flush_dns()
            elif action == "renew_ip":
                return network_helper.renew_ip()
            elif action == "traceroute":
                host = arguments.get("host", "8.8.8.8")
                return {"status": "success", "output": network_helper.traceroute(host)}
            elif action == "all":
                return {"status": "success", "output": {
                    "interfaces": network_helper.get_interfaces(),
                    "ipconfig": network_helper.get_ip_config(),
                    "connectivity": network_helper.check_connectivity(),
                    "wifi": network_helper.get_active_wifi(),
                    "usage": network_helper.get_network_usage()
                }}
            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown network action: {action}"}
                }
        except Exception as e:
            logger.error(f"NetworkTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
