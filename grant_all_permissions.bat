@echo off
setlocal enabledelayedexpansion

:: Define path to ADB
set ADB="C:\Users\yaswa\AppData\Local\Android\Sdk\platform-tools\adb.exe"

:: Check if ADB exists at the specified path
if not exist %ADB% (
    :: Try to find adb globally
    where adb >nul 2>nul
    if !errorlevel! equ 0 (
        set ADB=adb
    ) else (
        echo Android SDK ADB not found at %ADB% and not found globally.
        echo Please ensure Android SDK is installed and adjust the path in this script.
        pause
        exit /b 1
    )
)

echo Using ADB: %ADB%
echo Checking for connected Android devices...

:: Verify device connectivity and authorization
%ADB% devices > temp_devices.txt
findstr /C:"device" temp_devices.txt > nul
if %errorlevel% neq 0 (
    echo No active Android device detected.
    echo Please make sure your tablet is connected via USB and USB Debugging is enabled.
    del temp_devices.txt
    pause
    exit /b 1
)

findstr /C:"unauthorized" temp_devices.txt > nul
if %errorlevel% equ 0 (
    echo.
    echo [WARNING] Connected device is UNAUTHORIZED!
    echo Please check your tablet's screen and allow USB debugging for this computer.
    echo Select "Always allow from this computer" and tap "Allow".
    echo.
    del temp_devices.txt
    pause
    exit /b 1
)
del temp_devices.txt

set PACKAGE=com.aistudio.geminilive.abcde

echo.
echo Granting all permissions for %PACKAGE%...
echo.

:: 1. Bypass restricted settings (Android 13+)
echo [+] Allowing Restricted Settings...
%ADB% shell appops set %PACKAGE% ACCESS_RESTRICTED_SETTINGS allow

:: 2. Grant all runtime permissions
echo [+] Granting Runtime Permissions (Microphone, Contacts, Calls, Bluetooth, Location, Notifications)...
%ADB% shell pm grant %PACKAGE% android.permission.RECORD_AUDIO
%ADB% shell pm grant %PACKAGE% android.permission.READ_CONTACTS
%ADB% shell pm grant %PACKAGE% android.permission.CALL_PHONE
%ADB% shell pm grant %PACKAGE% android.permission.BLUETOOTH_CONNECT
%ADB% shell pm grant %PACKAGE% android.permission.ACCESS_FINE_LOCATION
%ADB% shell pm grant %PACKAGE% android.permission.ACCESS_COARSE_LOCATION
%ADB% shell pm grant %PACKAGE% android.permission.ACCESS_BACKGROUND_LOCATION
%ADB% shell pm grant %PACKAGE% android.permission.POST_NOTIFICATIONS

:: 3. All files access
echo [+] Granting Manage All Files access...
%ADB% shell appops set %PACKAGE% MANAGE_EXTERNAL_STORAGE allow

:: 4. Display over other apps (Overlay)
echo [+] Granting Overlay/Display Over Other Apps permission...
%ADB% shell appops set %PACKAGE% SYSTEM_ALERT_WINDOW allow

:: 5. Write Settings
echo [+] Granting Write Settings permission...
%ADB% shell appops set %PACKAGE% WRITE_SETTINGS allow

:: 6. Usage Stats
echo [+] Granting Usage Stats/Usage Data permission...
%ADB% shell appops set %PACKAGE% GET_USAGE_STATS allow

:: 7. Battery optimization
echo [+] Exempting from Battery Optimization...
%ADB% shell dumpsys deviceidle whitelist +%PACKAGE%

:: 8. Enable Notification Access (Notification Listener Service)
echo [+] Enabling Notification Listener access...
%ADB% shell settings put secure enabled_notification_listeners %PACKAGE%/com.example.service.WhatsAppNotificationService

:: 9. Enable Accessibility Service
echo [+] Enabling Accessibility Service...
%ADB% shell settings put secure enabled_accessibility_services %PACKAGE%/com.example.service.JarvisAccessibilityService
%ADB% shell settings put secure accessibility_enabled 1

:: 10. Device Administrator
echo [+] Activating Device Administrator...
%ADB% shell dpm set-active-admin %PACKAGE%/com.example.receiver.MyDeviceAdminReceiver

echo.
echo =========================================================
echo All permissions granted and services enabled successfully!
echo =========================================================
echo.
pause
