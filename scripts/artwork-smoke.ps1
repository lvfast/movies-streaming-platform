# Focused artwork smoke: place a synthetic poster in MinIO, queue an ARTWORK job via SQL, and let
# the running transcoder normalize it to a 600x900 READY asset.
$ErrorActionPreference = "Stop"
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

$movie = [guid]::NewGuid().ToString()
$upload = [guid]::NewGuid().ToString()
$asset = [guid]::NewGuid().ToString()
$job = [guid]::NewGuid().ToString()
$event = [guid]::NewGuid().ToString()
$slug = "artwork-smoke-" + (Get-Random -Minimum 1000 -Maximum 9999)
$key = "source/$movie/$upload/original"

$image = Join-Path $env:TEMP "worker-poster.jpg"
& ffmpeg -hide_banner -loglevel error -y -f lavfi -i "color=c=steelblue:s=1200x1800" -frames:v 1 $image
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed" }

$tmp = $env:TEMP -replace '\\','/'
docker run --rm --network media-streaming-platform_application -v "${tmp}:/data" --entrypoint sh `
  quay.io/minio/mc:latest -c "mc alias set local http://minio:9000 minioadmin minioadmin && mc cp /data/worker-poster.jpg local/media-source/$key" 2>&1 | Select-Object -Last 1

$sql = @"
INSERT INTO movie(id, slug, title, synopsis, release_year, maturity_rating, featured, published, management_mode, lifecycle, revision)
VALUES ('$movie', '$slug', 'Artwork Smoke', '', 2026, 'PG', false, false, 'MANAGED', 'DRAFT', 0);
INSERT INTO media_asset(id, movie_id, kind, state, source_key)
VALUES ('$asset', '$movie', 'POSTER', 'STORED', '$key');
INSERT INTO media_job(id, movie_id, asset_id, kind, state)
VALUES ('$job', '$movie', '$asset', 'ARTWORK', 'QUEUED');
INSERT INTO outbox_event(event_id, event_type, job_id, payload)
VALUES ('$event', 'artwork.requested.v1', '$job',
        '{"schemaVersion":1,"eventId":"$event","type":"artwork.requested.v1","jobId":"$job","occurredAt":"2026-09-12T10:00:00Z"}'::jsonb);
"@
$sql | docker exec -i media-streaming-platform-postgres-1 psql -U media_streaming -d media_streaming -q 2>&1 | Select-Object -Last 3

$state = ""
for ($i = 0; $i -lt 60; $i++) {
  $state = (docker exec media-streaming-platform-postgres-1 psql -U media_streaming -d media_streaming -t -A -c "select state from media_job where id='$job'" 2>&1 | Select-Object -Last 1)
  if ($state -in @("SUCCEEDED","FAILED")) { break }
  Start-Sleep -Seconds 2
}
$astate = (docker exec media-streaming-platform-postgres-1 psql -U media_streaming -d media_streaming -t -A -c "select state from media_asset where id='$asset'" 2>&1 | Select-Object -Last 1)
Write-Output "JOB: $state  ASSET: $astate"

$prefix = (docker exec media-streaming-platform-postgres-1 psql -U media_streaming -d media_streaming -t -A -c "select output_prefix from media_job_attempt where job_id='$job' order by attempt_number desc limit 1" 2>&1 | Select-Object -Last 1).Trim()
$out = Join-Path $env:TEMP "worker-artwork"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null
docker run --rm --network media-streaming-platform_application -v "${tmp}:/data" --entrypoint sh `
  quay.io/minio/mc:latest -c "mc alias set local http://minio:9000 minioadmin minioadmin && mc cp --recursive local/media-delivery/$prefix /data/worker-artwork/" 2>&1 | Select-Object -Last 1
& ffprobe -hide_banner -v error -select_streams v:0 -show_entries stream=codec_name,width,height -of csv=p=0 "$out/image.jpg" 2>&1 | Select-Object -First 2
if ($state -ne "SUCCEEDED") { throw "artwork job did not succeed (state=$state)" }
Write-Output "ARTWORK SMOKE OK: job=$job asset=$astate"
