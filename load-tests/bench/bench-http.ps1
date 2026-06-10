# MO HTTP benchmark for GET /api/analysis/{id} (cache + connection pool). Hits the orchestrator
# directly on the docker network (orchestrator has no host port; this isolates the orchestrator from
# the gateway/rate-limiter). Token from the deepfake-loadtest client; the target row is seeded so it
# is owned by the token subject (IDOR guard returns 200, not 404).
#
# Vary the optimization between runs via the orchestrator env, then re-run with a matching -Label:
#   cache hit  : CACHE_ENABLED=true   (default)
#   cache miss : CACHE_ENABLED=false
#   pool A/B   : DB_POOL_MAX=10  vs  DB_POOL_MAX=20
# (set the env, `docker compose --profile core --profile auth up -d orchestrator`, then run this.)
param(
    [string]$Label = "cache-hit",
    [int]$Concurrency = 50,
    [int]$Duration = 30,
    [string]$Secret = $env:LOADTEST_CLIENT_SECRET,
    [string]$Pg = "deepfake-detector-postgres-1",
    [string]$TargetId = "00000000-0000-0000-0000-0000000000aa"
)
if (-not $Secret) { throw "Set LOADTEST_CLIENT_SECRET (env) or pass -Secret." }

# 1. token + subject
$body = @{ grant_type='client_credentials'; client_id='deepfake-loadtest'; client_secret=$Secret }
$tok = (Invoke-RestMethod 'http://localhost:8180/realms/deepfake/protocol/openid-connect/token' -Method Post -Body $body -ContentType 'application/x-www-form-urlencoded').access_token
$payload = $tok.Split('.')[1].Replace('-','+').Replace('_','/'); switch ($payload.Length % 4) { 2 { $payload += '==' } 3 { $payload += '=' } }
$sub = ([System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json).sub
Write-Host "token subject: $sub"

# 2. seed the target row owned by the token subject (re-runnable)
$sql = "INSERT INTO analysis (id,user_id,file_id,file_key,type,status,verdict,confidence,created_at,updated_at) " +
       "VALUES ('$TargetId','$sub','bench-file','bench/x.mp4','FULL','COMPLETED','REAL',0.9000,NOW(),NOW()) " +
       "ON CONFLICT (id) DO UPDATE SET user_id=EXCLUDED.user_id;"
docker exec -i $Pg psql -U deepfake -d deepfake -c $sql | Out-Null

# 3. wrk on the docker network -> orchestrator:8082 (no host port needed)
Write-Host "=== wrk $Label : c=$Concurrency d=${Duration}s ==="
docker run --rm --network deepfake williamyeh/wrk `
    -t4 -c$Concurrency -d"${Duration}s" --latency `
    -H "Authorization: Bearer $tok" `
    "http://orchestrator:8082/api/analysis/$TargetId"
