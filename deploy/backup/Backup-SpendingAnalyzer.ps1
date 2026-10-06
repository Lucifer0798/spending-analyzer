<#
.SYNOPSIS
    Downloads a full backup of a deployed Spending Analyzer to this PC, keeping the newest copies.

.DESCRIPTION
    Signs in, fetches GET /api/backup (everything: accounts, transactions, categories, budgets,
    merchant memory, goals, tags, net worth, receipts...), checks the file is a complete backup,
    saves it as spending-analyzer-backup-YYYY-MM-DD.json, and deletes all but the newest -Keep.
    Restore one from the app's Manage page -> Backup -> Restore.

    The password is never written in plain text: run once with -SavePassword and it is stored
    encrypted with Windows DPAPI, readable only by this Windows user on this PC. Works on the
    Windows PowerShell 5.1 that ships with Windows.

.EXAMPLE
    # One-time: store the password (prompts for it).
    .\Backup-SpendingAnalyzer.ps1 -Url https://129-146-12-34.sslip.io -SavePassword

.EXAMPLE
    # A backup run -- what the scheduled task does every night.
    .\Backup-SpendingAnalyzer.ps1 -Url https://129-146-12-34.sslip.io
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Url,

    [string]$Destination = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'SpendingAnalyzerBackups'),

    # How many daily backups to keep; older ones are deleted after a successful run.
    [ValidateRange(1, 3650)]
    [int]$Keep = 30,

    [string]$CredentialPath = (Join-Path $env:LOCALAPPDATA 'SpendingAnalyzer\backup-password.xml'),

    # Prompt for the password and store it (DPAPI-encrypted), then exit.
    [switch]$SavePassword
)

$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 can default to TLS 1.0/1.1, which Caddy refuses -- add 1.2 to whatever
# is already enabled (naming Tls13 here would fail outright on older .NET builds).
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Url = $Url.TrimEnd('/')
$logPath = Join-Path $Destination 'backup.log'

function Write-Log([string]$message) {
    $line = '{0:yyyy-MM-dd HH:mm:ss}  {1}' -f (Get-Date), $message
    Write-Host $line
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Add-Content -Path $logPath -Value $line -Encoding UTF8
}

if ($SavePassword) {
    $secure = Read-Host -AsSecureString -Prompt "Password for $Url"
    New-Item -ItemType Directory -Force -Path (Split-Path $CredentialPath) | Out-Null
    # Export-Clixml protects a SecureString with DPAPI: only this user on this machine can read it.
    $secure | Export-Clixml -Path $CredentialPath
    Write-Host "Saved (encrypted for $env:USERNAME on $env:COMPUTERNAME) to $CredentialPath"
    exit 0
}

if (-not (Test-Path $CredentialPath)) {
    Write-Log "FAILED: no saved password. Run once with -SavePassword first."
    exit 1
}

try {
    $secure = Import-Clixml -Path $CredentialPath
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))

    # 1. Any GET issues the CSRF cookie that the sign-in POST has to echo back as a header.
    $null = Invoke-WebRequest -Uri "$Url/api/auth/status" -UseBasicParsing -SessionVariable session -TimeoutSec 60
    $token = ($session.Cookies.GetCookies($Url) | Where-Object Name -eq 'XSRF-TOKEN').Value
    if (-not $token) { throw "The server didn't issue a CSRF token -- is $Url this app?" }

    # 2. Sign in. 429 means too many wrong passwords recently; 401 means the saved one is wrong.
    $body = @{ password = $plain } | ConvertTo-Json -Compress
    $null = Invoke-WebRequest -Uri "$Url/api/auth/login" -Method Post -Body $body -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $token } -WebSession $session -UseBasicParsing -TimeoutSec 60
    $plain = $null

    # 3. Download to a temporary name, so a failed or partial download never replaces a good file.
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $stamp = Get-Date -Format 'yyyy-MM-dd'
    $final = Join-Path $Destination "spending-analyzer-backup-$stamp.json"
    $partial = "$final.partial"
    Invoke-WebRequest -Uri "$Url/api/backup" -WebSession $session -UseBasicParsing -OutFile $partial -TimeoutSec 600

    # 4. Check it's a whole backup, not an error page: it must parse and carry a version number.
    $parsed = Get-Content -Raw -Encoding UTF8 -Path $partial | ConvertFrom-Json
    if ($null -eq $parsed.version) { throw "Downloaded file isn't a backup (no version field)." }
    Move-Item -Force -Path $partial -Destination $final

    $sizeKb = [math]::Round((Get-Item $final).Length / 1KB)
    $txCount = @($parsed.transactions).Count
    Write-Log "OK: $final ($sizeKb KB, backup version $($parsed.version), $txCount transactions)"

    # 5. Best effort sign-out; the token rotates on sign-in, so read the fresh one first.
    try {
        $null = Invoke-WebRequest -Uri "$Url/api/auth/status" -WebSession $session -UseBasicParsing -TimeoutSec 60
        $token = ($session.Cookies.GetCookies($Url) | Where-Object Name -eq 'XSRF-TOKEN').Value
        $null = Invoke-WebRequest -Uri "$Url/api/auth/logout" -Method Post -Headers @{ 'X-XSRF-TOKEN' = $token } `
            -WebSession $session -UseBasicParsing -TimeoutSec 60
    } catch { }

    # 6. Prune: keep the newest $Keep backups. Only after a successful run, so a run of failures
    #    can never delete the last good copies.
    Get-ChildItem -Path $Destination -Filter 'spending-analyzer-backup-*.json' |
        Sort-Object Name -Descending |
        Select-Object -Skip $Keep |
        ForEach-Object { Remove-Item $_.FullName; Write-Log "Pruned $($_.Name)" }
    exit 0
}
catch {
    $status = $null
    if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
    $hint = switch ($status) {
        401 { ' -- the saved password was rejected; run again with -SavePassword.' }
        429 { ' -- signed out after too many wrong passwords; it will retry next run.' }
        default { '' }
    }
    if (Test-Path variable:partial) { Remove-Item -Force -ErrorAction SilentlyContinue $partial }
    Write-Log "FAILED: $($_.Exception.Message)$hint"
    exit 1
}
