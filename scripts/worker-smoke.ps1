# Focused worker smoke: place a synthetic source in MinIO, queue a TRANSCODE job via SQL, and let
# the running transcoder (RabbitMQ -> claim -> ffmpeg -> upload -> completed) drive the backend to
# READY. Works around the local Docker Desktop host-port forwarding issue by using the internal
# application network for MinIO access.
$ErrorActionPreference = "Stop"
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

$movie = [guid]::NewGuid().ToString()
$upload = [guid]::NewGuid().ToString()
$version = [guid]::NewGuid().ToString()
$job = [guid]::NewGuid().ToString()
$event = [guid]::NewGuid().ToString()
$slug = "worker-smoke-" + (Get-Random -Minimum 1000 -Maximum 9999)
$key = "source/$movie/$upload/original"

# 1. Generate a short synthetic source and copy it into MinIO over the internal network.
$video = Join-Path $env:TEMP "worker-source.mp4"
& ffmpeg -hide_banner -loglevel error -y `
  -f lavfi -i "testsrc=duration=3:size=640x360:rate=24" `
  -f lavfi -i "sine=frequency=440:duration=3" `
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest $video
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed" }

$tmp = $env:TEMP -replace '\\','/'
docker run --rm --network media-streaming-platform_application -v "${tmp}:/data" --entrypoint sh `
  quay.io/minio/mc:latest -c "mc alias set local http://minio:9000 minioadmin minioadmin && mc cp /data/worker-source.mp4 local/media-source/$key" 2>&1 | Select-Object -Last 2

# 2. Queue a TRANSCODE job and outbox command directly (bypasses the HTTP upload for the smoke).
$sql = @"
INSERT INTO movie(id, slug, title, synopsis, release_year, maturity_rating, featured, published, management_mode, lifecycle, revision)
VALUES ('$movie', '$slug', 'Worker Smoke', '', 2026, 'PG', false, false, 'MANAGED', 'DRAFT', 0);
INSERT INTO media_version(id, movie_id, state, source_key)
VALUES ('$version', '$movie', 'QUEUED', '$key');
INSERT INTO media_job(id, movie_id, media_version_id, kind, state)
VALUES ('$job', '$movie', '$version', 'TRANSCODE', 'QUEUED');
INSERT INTO outbox_event(event_id, event_type, job_id, payload)
VALUES ('$event', 'transcode.requested.v1', '$job',
        '{"schemaVersion":1,"eventId":"$event","type":"transcode.requested.v1","jobId":"$job","occurredAt":"2026-09-12T10:00:00Z"}'::jsonb);
"@
$sql | docker exec -i media-streaming-platform-postgres-1 psql -U media_streaming -d media_streaming -q 2>&1 | Select-Object -Last 5

# 3. Wait for the worker to process (backend marks SUCCEEDED + version READY).
$state = ""
for ($i = 0; $i -lt 60; $i++) {
  $state = (docker exec media-streaming-platform-postgres-1 psql -U media_streaming -d media_streaming -t -A -c "select state from media_job where id='$job'" 2>&1 | Select-Object -Last 1)
  if ($state -in @("SUCCEEDED","FAILED")) { break }
  Start-Sleep -Seconds 2
}
$vstate = (docker exec media-streaming-platform-postgres-1 psql -U media_streaming -d media_streaming -t -A -c "select state from media_version where id='$version'" 2>&1 | Select-Object -Last 1)
Write-Output "JOB: $state  VERSION: $vstate"

# 4. Locate the attempt prefix and decode the generated HLS from the delivery bucket.
$prefix = (docker exec media-streaming-platform-postgres-1 psql -U media_streaming -d media_streaming -t -A -c "select output_prefix from media_job_attempt where job_id='$job' order by attempt_number desc limit 1" 2>&1 | Select-Object -Last 1).Trim()
Write-Output "PREFIX: $prefix"
if (-not $prefix) { throw "no attempt prefix found" }

$out = Join-Path $env:TEMP "worker-hls"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null
docker run --rm --network media-streaming-platform_application -v "${tmp}:/data" --entrypoint sh `
  quay.io/minio/mc:latest -c "mc alias set local http://minio:9000 minioadmin minioadmin && mc cp --recursive local/media-delivery/$prefix /data/worker-hls/" 2>&1 | Select-Object -Last 2

& ffprobe -hide_banner -v error -show_entries stream=codec_name,codec_type -of csv=p=0 "$out/index.m3u8" 2>&1 | Select-Object -First 5
if ($state -ne "SUCCEEDED") { throw "job did not succeed (state=$state)" }
Write-Output "WORKER SMOKE OK: job=$job version=$vstate"
