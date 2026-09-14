# Local P3 happy-path smoke: synthetic video -> RabbitMQ -> transcoder -> READY HLS.
# Prereqs: full stack is up via
#   docker compose --env-file .env.example -f compose.yml -f compose.local.yml -f compose.media.local.yml up -d --build
# and the media-source/media-delivery buckets exist (see docs/runbooks/media-local.md).
$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$suffix = Get-Random -Minimum 1000 -Maximum 9999
$username = "p3smoke$suffix"
$password = "HappyPathPass123"

# Ensure ffmpeg is on PATH for the synthetic source.
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

function Json($obj) { $obj | ConvertTo-Json -Compress -Depth 10 }

# 1. Register and grant ADMIN (local smoke grants via SQL; the operator command is in the runbook).
$register = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/register" `
  -ContentType "application/json" -Body (Json @{ username = $username; password = $password })
$token = $register.accessToken
if (-not $token) { throw "registration failed" }

docker exec media-streaming-platform-postgres-1 psql -U media_streaming -d media_streaming -q -c `
  "INSERT INTO user_role(user_id, role) SELECT id, 'ADMIN' FROM app_user WHERE username='$username' ON CONFLICT DO NOTHING;" | Out-Null

$login = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" `
  -ContentType "application/json" -Body (Json @{ username = $username; password = $password })
$token = $login.accessToken
$headers = @{ Authorization = "Bearer $token" }

# 2. Generate a short synthetic source video (1 part, well under 8 MiB).
$video = Join-Path $env:TEMP "p3-source.mp4"
& ffmpeg -hide_banner -loglevel error -y `
  -f lavfi -i "testsrc=duration=2:size=320x240:rate=24" `
  -f lavfi -i "sine=frequency=440:duration=2" `
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest $video
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed to generate source" }
$size = (Get-Item $video).Length

# 3. Create a managed movie.
$slug = "p3smoke$suffix"
$movie = Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/movies" `
  -Headers ($headers + @{ "Idempotency-Key" = "movie-$suffix" }) `
  -ContentType "application/json" `
  -Body (Json @{ title="P3 smoke"; slug=$slug; synopsis="Happy path"; releaseYear=2026; maturityRating="PG"; genreIds=@(); featured=$false })
$movieId = $movie.id

# 4. Create an upload session.
$fingerprint = "sha256:" + ("a" * 64)
$upload = Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/movies/$movieId/uploads" `
  -Headers ($headers + @{ "Idempotency-Key" = "upload-$suffix" }) `
  -ContentType "application/json" `
  -Body (Json @{ kind="VIDEO"; fileName="source.mp4"; contentType="video/mp4"; sizeBytes=$size; resumeFingerprint=$fingerprint })
$uploadId = $upload.id

# 5. Sign one part, upload the bytes, complete.
$signed = Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/uploads/$uploadId/part-urls" `
  -Headers $headers -ContentType "application/json" -Body (Json @{ partNumbers = @(1) })
$partUrl = @($signed.items)[0].url
if (-not $partUrl) { throw "part-urls response did not contain a signed URL" }
$bytes = [System.IO.File]::ReadAllBytes($video)
$web = [System.Net.WebClient]::new()
$web.Headers.Add("Content-Type", "video/mp4")
$web.UploadData($partUrl, "PUT", $bytes) | Out-Null

$completed = Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/uploads/$uploadId/complete" `
  -Headers ($headers + @{ "Idempotency-Key" = "complete-$suffix" })

# 6. Poll the job until terminal.
$jobId = $completed.jobId
if (-not $jobId) {
  $session = Invoke-RestMethod -Method Get -Uri "$base/api/v1/admin/uploads/$uploadId" -Headers $headers
  $jobId = $session.jobId
}
$state = ""
for ($i = 0; $i -lt 60; $i++) {
  $job = Invoke-RestMethod -Method Get -Uri "$base/api/v1/admin/jobs/$jobId" -Headers $headers
  $state = $job.state
  if ($state -in @("SUCCEEDED","FAILED")) { break }
  Start-Sleep -Seconds 2
}
Write-Output "JOB: $state"

$version = Invoke-RestMethod -Method Get -Uri "$base/api/v1/admin/movies/$movieId/versions" -Headers $headers
Write-Output "VERSION: $($version.items[0].state)"

if ($state -ne "SUCCEEDED") { throw "job did not succeed (state=$state)" }

# 7. Decode the generated HLS as the assertion.
$prefix = (docker exec media-streaming-platform-postgres-1 psql -U media_streaming -d media_streaming -t -A -c "select output_prefix from media_job_attempt where job_id='$jobId' order by attempt_number desc limit 1" 2>&1 | Select-Object -Last 1).Trim()
$hls = Join-Path $env:TEMP "happy-hls"
if (Test-Path $hls) { Remove-Item $hls -Recurse -Force }
New-Item -ItemType Directory -Path $hls | Out-Null
$tmp = $env:TEMP -replace '\\','/'
docker run --rm --network media-streaming-platform_application -v "${tmp}:/data" --entrypoint sh `
  quay.io/minio/mc:latest -c "mc alias set local http://minio:9000 minioadmin minioadmin && mc cp --recursive local/media-delivery/$prefix /data/happy-hls/" 2>&1 | Select-Object -Last 1
$codecs = ((& ffprobe -hide_banner -v error -show_entries stream=codec_name -of csv=p=0 "$hls/index.m3u8" 2>&1) -join " ").Trim()
Write-Output "DECODED CODECS: $codecs"
if ($codecs -notmatch "h264" -or $codecs -notmatch "aac") { throw "HLS did not decode to h264/aac: $codecs" }
Write-Output "HAPPY PATH OK: movie=$movieId job=$jobId"
