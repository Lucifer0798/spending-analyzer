<#
.SYNOPSIS
    Schedules Backup-SpendingAnalyzer.ps1 to run every night on this PC.

.DESCRIPTION
    Creates (or replaces) a Windows scheduled task for the current user. If the PC is off or
    asleep at the scheduled time, the task runs as soon as it's next available, and it waits for a
    network connection. It runs only while you're signed in to Windows -- that is what lets it read
    the DPAPI-encrypted password without storing your Windows password in the task.

    Run Backup-SpendingAnalyzer.ps1 -SavePassword first. Remove the task later with:
        Unregister-ScheduledTask -TaskName 'Spending Analyzer backup' -Confirm:$false

.EXAMPLE
    .\Register-BackupTask.ps1 -Url https://129-146-12-34.sslip.io -At 21:00
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Url,

    # Time of day, 24-hour, e.g. 21:00.
    [string]$At = '21:00',

    [string]$Destination = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'SpendingAnalyzerBackups'),

    [int]$Keep = 30,

    [string]$TaskName = 'Spending Analyzer backup'
)

$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'Backup-SpendingAnalyzer.ps1'
if (-not (Test-Path $script)) { throw "Can't find $script next to this file." }

$arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}" -Url "{1}" -Destination "{2}" -Keep {3}' -f `
    $script, $Url, $Destination, $Keep

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
$trigger = New-ScheduledTaskTrigger -Daily -At ([datetime]::ParseExact($At, 'HH:mm', $null))
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RunOnlyIfNetworkAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -DontStopIfGoingOnBatteries -AllowStartIfOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Description "Nightly backup of $Url to $Destination" -Force | Out-Null

Write-Host "Scheduled '$TaskName' daily at $At (or at the next chance if the PC was off)."
Write-Host "Backups go to $Destination; see backup.log there for each run's result."
Write-Host "Run it now to check: Start-ScheduledTask -TaskName '$TaskName'"
