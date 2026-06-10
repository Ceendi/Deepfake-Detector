# Run the D4 upload load test (non-GUI) and generate the HTML dashboard. Run once per variant with a
# different -Label (e.g. r1, r2) so the JTL/report files don't collide.
param(
    [string]$Label = "r1",
    [string]$VHost = "localhost",
    [int]$Port = 8080,
    [int]$Threads = 50,
    [int]$Duration = 60,
    [int]$Rampup = 5,
    [string]$KcHost = "localhost",
    [int]$KcPort = 8180,
    [string]$Sample = "$PSScriptRoot\sample-5mb.mp4",
    [string]$Secret = $env:LOADTEST_CLIENT_SECRET
)
if (-not $Secret) { throw "Set LOADTEST_CLIENT_SECRET (env) or pass -Secret. The JMeter token request needs it." }
if (-not (Get-Command jmeter -ErrorAction SilentlyContinue)) { throw "JMeter not on PATH. Install Apache JMeter 5.6.x." }
if (-not (Test-Path $Sample)) { throw "Sample not found: $Sample. Run generate-sample.ps1 first." }

$jtl = "$PSScriptRoot\results-$Label.jtl"
$report = "$PSScriptRoot\report-$Label"
if (Test-Path $jtl) { Remove-Item $jtl }
if (Test-Path $report) { Remove-Item $report -Recurse }

# Each -J arg is double-quoted so PowerShell expands the variable into a single token (an unquoted
# -Jkey=$var is not reliably expanded for native .cmd shims on Windows PowerShell 5.1).
jmeter -n -t "$PSScriptRoot\file-service-upload.jmx" `
    "-Jhost=$VHost" "-Jport=$Port" "-Jthreads=$Threads" "-Jduration=$Duration" "-Jrampup=$Rampup" `
    "-JkcHost=$KcHost" "-JkcPort=$KcPort" "-JsampleFile=$Sample" "-JloadtestSecret=$Secret" `
    "-l" "$jtl" "-e" "-o" "$report"
Write-Host "Done. JTL: $jtl   HTML dashboard: $report\index.html"
