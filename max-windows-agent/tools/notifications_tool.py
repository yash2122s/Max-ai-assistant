import logging
import subprocess
import time
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.Notifications")


class NotificationsHelper:
    def send_toast(self, title: str, message: str, app_id: str = "MAX Windows Agent", duration: str = "short", silent: bool = False) -> dict:
        try:
            duration_sec = "short" if duration == "short" else "long"
            silent_flag = " -silent" if silent else ""

            ps_script = f'''
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null
            $template = [Windows.UI.Notifications.ToastTemplateType]::ToastText02
            $xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent($template)
            $textNodes = $xml.GetElementsByTagName("text")
            $textNodes.Item(0).AppendChild($xml.CreateTextNode("{title}")) > $null
            $textNodes.Item(1).AppendChild($xml.CreateTextNode("{message}")) > $null

            $toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
            [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("{app_id}").Show($toast)
            '''
            subprocess.run(["powershell", "-NoProfile", "-Command", ps_script], capture_output=True, timeout=15)
            return {"status": "success", "message": f"Notification sent: {title}"}
        except Exception as e:
            logger.warning(f"PowerShell toast failed, trying legacy method: {e}")
            try:
                subprocess.run([
                    "powershell", "-NoProfile", "-Command",
                    f'Write-Output "`a{title}: {message}"; [System.Reflection.Assembly]::LoadWithPartialName("System.Windows.Forms") | Out-Null; [System.Windows.Forms.MessageBox]::Show("{message}", "{title}")'
                ], capture_output=True, timeout=10)
                return {"status": "success", "message": "Notification sent via MessageBox fallback"}
            except Exception as e2:
                return {"status": "failed", "error": f"Failed to send notification: {e2}"}

    def send_balloon(self, title: str, message: str, icon: str = "info") -> dict:
        try:
            icon_type = {
                "info": "Info",
                "warning": "Warning",
                "error": "Error",
                "none": "None"
            }.get(icon.lower(), "Info")

            ps_script = f'''
            Add-Type -AssemblyName System.Windows.Forms
            $balloon = New-Object System.Windows.Forms.NotifyIcon
            $balloon.Icon = [System.Drawing.SystemIcons]::{icon_type}
            $balloon.BalloonTipIcon = [System.Windows.Forms.ToolTipIcon]::{icon_type}
            $balloon.BalloonTipTitle = "{title}"
            $balloon.BalloonTipText = "{message}"
            $balloon.Visible = $true
            $balloon.ShowBalloonTip(5000)
            Start-Sleep -Seconds 5
            $balloon.Visible = $false
            $balloon.Dispose()
            '''
            subprocess.run(["powershell", "-NoProfile", "-Command", ps_script], capture_output=True, timeout=10)
            return {"status": "success", "message": f"Balloon notification sent: {title}"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def send_speech(self, text: str, voice: str = "", speed: int = 0) -> dict:
        try:
            voice_param = f' -Voice (Get-WinUserLanguageList)[0].Voice' if not voice else f' -Voice "{voice}"'
            speed_param = f' -Rate {speed}' if speed != 0 else ''

            ps_script = f'''
            Add-Type -AssemblyName System.Speech
            $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
            $synth.SetOutputToDefaultAudioDevice()
            $synth.Speak("{text}")
            $synth.Dispose()
            '''
            subprocess.run(["powershell", "-NoProfile", "-Command", ps_script], capture_output=True, timeout=30)
            return {"status": "success", "message": "Speech output completed"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}


notifications_helper = NotificationsHelper()


class NotificationsTool(BaseTool):
    @property
    def name(self) -> str:
        return "notifications"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running notifications/{action}...")
        try:
            if action == "toast":
                title = arguments.get("title", "MAX Agent")
                message = arguments.get("message", arguments.get("body", ""))
                duration = arguments.get("duration", "short")
                silent = arguments.get("silent", False)
                if isinstance(silent, str):
                    silent = silent.lower() == "true"
                if not message:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'message' parameter"}}
                result = notifications_helper.send_toast(title, message, duration=duration, silent=silent)
                return result
            elif action == "balloon":
                title = arguments.get("title", "MAX Agent")
                message = arguments.get("message", arguments.get("body", ""))
                icon = arguments.get("icon", "info")
                if not message:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'message' parameter"}}
                result = notifications_helper.send_balloon(title, message, icon)
                return result
            elif action == "speak":
                text = arguments.get("text", arguments.get("message", ""))
                if not text:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'text' parameter"}}
                result = notifications_helper.send_speech(text)
                return result
            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown notifications action: {action}"}
                }
        except Exception as e:
            logger.error(f"NotificationsTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
