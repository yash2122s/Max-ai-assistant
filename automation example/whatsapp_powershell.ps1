# WhatsApp Windows Native PowerShell Automation Script
param (
    [string]$Contact = "yaswanth",
    [string]$Message = "hello",
    [switch]$Send = $false
)

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "🚀 WhatsApp Windows Automation (PowerShell Native)" -ForegroundColor Green
Write-Host "🎯 Target Contact : $Contact" -ForegroundColor Yellow
Write-Host "💬 Message Text   : $Message" -ForegroundColor Yellow
Write-Host "==================================================" -ForegroundColor Cyan

$wscript = New-Object -ComObject WScript.Shell

# Step 1 & 2: Press Windows key and search WhatsApp
Write-Host "[Step 1 & 2] Opening Start and searching WhatsApp..." -ForegroundColor Gray
$wscript.SendKeys("^{ESC}") # Opens Start Menu
Start-Sleep -Milliseconds 600
$wscript.SendKeys("WhatsApp")
Start-Sleep -Milliseconds 800

# Step 3: Launch
Write-Host "[Step 3] Launching WhatsApp..." -ForegroundColor Gray
$wscript.SendKeys("{ENTER}")

# Step 4: Wait for app load
Write-Host "[Step 4] Waiting for WhatsApp to load..." -ForegroundColor Gray
Start-Sleep -Seconds 3

# Step 5: Focus Search (Ctrl + F)
Write-Host "[Step 5] Triggering Search (Ctrl + F)..." -ForegroundColor Gray
$wscript.SendKeys("^f")
Start-Sleep -Milliseconds 800

# Step 6: Search Contact
Write-Host "[Step 6] Searching for contact: $Contact..." -ForegroundColor Gray
$wscript.SendKeys($Contact)
Start-Sleep -Milliseconds 1200

# Step 7: Select & Open Chat
Write-Host "[Step 7] Opening chat..." -ForegroundColor Gray
$wscript.SendKeys("{DOWN}")
Start-Sleep -Milliseconds 300
$wscript.SendKeys("{ENTER}")
Start-Sleep -Milliseconds 800

# Step 8: Type Message
Write-Host "[Step 8] Typing message: $Message..." -ForegroundColor Gray
$wscript.SendKeys($Message)
Start-Sleep -Milliseconds 500

if ($Send) {
    Write-Host "Sending message..." -ForegroundColor Gray
    $wscript.SendKeys("{ENTER}")
}

Write-Host "==================================================" -ForegroundColor Green
Write-Host "🎉 TASK DONE! (టాస్క్ విజయవంతంగా పూర్తయింది)" -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Green