# Generate a real ~5 MB MP4 (H.264 + AAC) that passes file-service validation (Tika magic bytes +
# ffprobe + MIME whitelist). A random blob would be rejected with 422 and never reach S3, so the
# benchmark would measure the rejection path instead of a real upload.
param(
    [string]$Out = "$PSScriptRoot\sample-5mb.mp4",
    [int]$Seconds = 55,
    [string]$Bitrate = "900k"
)
ffmpeg -y -f lavfi -i "testsrc=duration=${Seconds}:size=1280x720:rate=30" `
    -f lavfi -i "sine=frequency=440:duration=${Seconds}" `
    -c:v libx264 -b:v $Bitrate -pix_fmt yuv420p -c:a aac -movflags +faststart -shortest $Out
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed (exit $LASTEXITCODE)" }
$mb = (Get-Item $Out).Length / 1MB
Write-Host ("Generated {0} ({1:N2} MB). Tune -Bitrate/-Seconds to hit ~5 MB." -f $Out, $mb)
