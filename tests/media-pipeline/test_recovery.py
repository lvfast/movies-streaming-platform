#!/usr/bin/env python3
"""P4 recovery integration scenarios against the disposable Compose stack.

Run from the repository root:

    python tests/media-pipeline/test_recovery.py MediaRecoveryTest.test_worker_dies_after_claim -v
    python tests/media-pipeline/test_recovery.py MediaRecoveryTest.test_duplicate_and_late_results -v
    python tests/media-pipeline/test_recovery.py MediaRecoveryTest.test_upload_completion_response_loss -v
    python tests/media-pipeline/test_recovery.py -v

Requires Docker, ffmpeg on PATH (or discoverable from the machine/user PATH) and the Python
packages in requirements (requests, boto3, pika). See README.md.
"""

import json
import os
import shutil
import subprocess
import time
import unittest
import uuid

import boto3
import pika
import requests
from botocore.config import Config

PROJECT = "media-recovery-test"
BACKEND = "http://127.0.0.1:18080"
MINIO = "http://127.0.0.1:19000"
AMQP_HOST = "127.0.0.1"
AMQP_PORT = 5673
SOURCE_BUCKET = "media-source"
DELIVERY_BUCKET = "media-delivery"
ACCESS_KEY = "minioadmin"
SECRET_KEY = "minioadmin"
AMQP_USER = "media"
AMQP_PASSWORD = "media-test"
WORKER_CREDENTIAL = "worker-test-credential"

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
COMPOSE_FILE = os.path.join(REPO_ROOT, "tests", "media-pipeline", "compose.test.yml")


def run(cmd, **kwargs):
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def compose(*args):
    return run(["docker", "compose", "-p", PROJECT, "-f", COMPOSE_FILE] + list(args))


def psql(sql):
    result = run([
        "docker", "compose", "-p", PROJECT, "exec", "-T", "postgres",
        "psql", "-U", "media_streaming", "-d", "media_streaming", "-t", "-A", "-c", sql,
    ])
    if result.returncode != 0:
        raise RuntimeError("psql failed: " + result.stderr)
    return result.stdout.strip()


def psql_script(sql):
    result = run([
        "docker", "compose", "-p", PROJECT, "exec", "-T", "postgres",
        "psql", "-U", "media_streaming", "-d", "media_streaming", "-q", "-v", "ON_ERROR_STOP=1",
    ], input=sql)
    if result.returncode != 0:
        raise RuntimeError("psql script failed: " + result.stderr)


def s3_client():
    return boto3.client(
        "s3",
        endpoint_url=MINIO,
        region_name="us-east-1",
        aws_access_key_id=ACCESS_KEY,
        aws_secret_access_key=SECRET_KEY,
        config=Config(s3={"addressing_style": "path"}),
    )


def find_ffmpeg():
    path = shutil.which("ffmpeg")
    if path:
        return path
    try:
        import winreg
        collected = []
        for hive, key in (
            (winreg.HKEY_LOCAL_MACHINE, r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment"),
            (winreg.HKEY_CURRENT_USER, "Environment"),
        ):
            try:
                with winreg.OpenKey(hive, key) as handle:
                    collected.append(winreg.QueryValueEx(handle, "Path")[0])
            except OSError:
                pass
        for directory in ";".join(collected).split(";"):
            candidate = os.path.join(directory, "ffmpeg.exe")
            if directory and os.path.isfile(candidate):
                return candidate
    except Exception:
        pass
    raise RuntimeError("ffmpeg not found; install it or add it to PATH")


def generate_source(destination, seconds=40):
    subprocess.run([
        find_ffmpeg(), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", f"testsrc=duration={seconds}:size=640x360:rate=24",
        "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
        destination,
    ], check=True)


def publish_result(event_type, event_id, job_id, attempt_id, sequence, payload):
    connection = pika.BlockingConnection(pika.ConnectionParameters(
        host=AMQP_HOST, port=AMQP_PORT,
        credentials=pika.PlainCredentials(AMQP_USER, AMQP_PASSWORD)))
    try:
        channel = connection.channel()
        body = json.dumps({
            "schemaVersion": 1,
            "eventId": event_id,
            "type": event_type,
            "jobId": job_id,
            "attemptId": attempt_id,
            "sequence": sequence,
            "occurredAt": "2026-09-12T10:00:00Z",
            "payload": payload,
        })
        channel.basic_publish(
            exchange="media.results",
            routing_key=event_type,
            body=body,
            properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
        )
    finally:
        connection.close()


class MediaRecoveryTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        compose("up", "-d", "--build")
        cls._wait_for(lambda: requests.get(f"{BACKEND}/actuator/health/liveness", timeout=2).status_code == 200,
                      "backend liveness", 300)
        client = s3_client()
        for bucket in (SOURCE_BUCKET, DELIVERY_BUCKET):
            try:
                client.create_bucket(Bucket=bucket)
            except client.exceptions.BucketAlreadyOwnedByYou:
                pass

    @classmethod
    def tearDownClass(cls):
        compose("down", "-v")

    def setUp(self):
        psql_script("""
            delete from outbox_event;
            delete from inbox_event;
            delete from media_job_attempt;
            delete from upload_session;
            delete from media_job;
            delete from media_asset;
            delete from media_version;
            delete from operation_request;
            delete from audit_event;
            delete from movie where management_mode = 'MANAGED';
        """)

    # --- helpers ---------------------------------------------------------

    def register_admin(self):
        username = "recov" + uuid.uuid4().hex[:10]
        response = requests.post(f"{BACKEND}/api/v1/auth/register", json={
            "username": username, "password": "RecoveryPass123"})
        response.raise_for_status()
        token = response.json()["accessToken"]
        psql(f"insert into user_role(user_id, role) "
             f"select id, 'ADMIN' from app_user where username='{username}' "
             f"on conflict do nothing")
        return token

    def create_movie(self, token):
        slug = "recov" + uuid.uuid4().hex[:10]
        response = requests.post(f"{BACKEND}/api/v1/admin/movies",
                                 headers={"Authorization": f"Bearer {token}",
                                          "Idempotency-Key": "m-" + uuid.uuid4().hex},
                                 json={"title": "Recovery", "slug": slug, "synopsis": "Recovery",
                                       "releaseYear": 2026, "maturityRating": "PG",
                                       "genreIds": [], "featured": False})
        response.raise_for_status()
        return response.json()["id"]

    def create_upload(self, token, movie_id, size):
        response = requests.post(f"{BACKEND}/api/v1/admin/movies/{movie_id}/uploads",
                                 headers={"Authorization": f"Bearer {token}",
                                          "Idempotency-Key": "u-" + uuid.uuid4().hex},
                                 json={"kind": "VIDEO", "fileName": "source.mp4",
                                       "contentType": "video/mp4", "sizeBytes": size,
                                       "resumeFingerprint": "sha256:" + "a" * 64})
        response.raise_for_status()
        return response.json()

    def put_part(self, token, upload_id, data):
        response = requests.post(f"{BACKEND}/api/v1/admin/uploads/{upload_id}/part-urls",
                                 headers={"Authorization": f"Bearer {token}"},
                                 json={"partNumbers": [1]})
        response.raise_for_status()
        url = response.json()["items"][0]["url"]
        part = requests.put(url, data=data)
        self.assertIn(part.status_code, (200, 204), f"part PUT failed: {part.status_code} {part.text}")

    def complete_upload(self, token, upload_id):
        response = requests.post(f"{BACKEND}/api/v1/admin/uploads/{upload_id}/complete",
                                 headers={"Authorization": f"Bearer {token}",
                                          "Idempotency-Key": "c-" + uuid.uuid4().hex})
        response.raise_for_status()
        return response.json()

    def job_state(self, job_id):
        return psql(f"select state from media_job where id='{job_id}'")

    def version_state(self, job_id):
        return psql(f"select state from media_version "
                    f"where id=(select media_version_id from media_job where id='{job_id}')")

    def attempt_number(self, job_id):
        return int(psql(f"select attempt_number from media_job where id='{job_id}'"))

    def claim(self, job_id):
        response = requests.post(f"{BACKEND}/internal/v1/jobs/{job_id}/claim",
                                 headers={"Authorization": f"Bearer {WORKER_CREDENTIAL}",
                                          "Content-Type": "application/json"},
                                 json={"workerId": "worker-harness"})
        response.raise_for_status()
        return response.json()

    @staticmethod
    def _wait_for(predicate, description, timeout):
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            try:
                value = predicate()
            except Exception as error:  # noqa: BLE001 - surface at timeout
                last = error
                value = False
            if value:
                return value
            time.sleep(0.25)
        raise AssertionError(f"timed out waiting for {description}: {last}")

    # --- scenarios -------------------------------------------------------

    def test_worker_dies_after_claim(self):
        token = self.register_admin()
        movie_id = self.create_movie(token)
        source = os.path.join(os.environ.get("TEMP", "/tmp"), f"recov-{uuid.uuid4().hex}.mp4")
        try:
            generate_source(source)
            size = os.path.getsize(source)
            upload = self.create_upload(token, movie_id, size)
            with open(source, "rb") as handle:
                self.put_part(token, upload["id"], handle.read())
            completed = self.complete_upload(token, upload["id"])
            job_id = completed["jobId"]

            # Wait for the durable claim, then hard-kill the worker mid-processing.
            self._wait_for(lambda: self.job_state(job_id) == "RUNNING", "worker claim", 120)
            compose("kill", "transcoder")

            # Lease expiry -> RETRY_WAIT, then advance the retry window to skip the 60s delay.
            self._wait_for(lambda: self.job_state(job_id) == "RETRY_WAIT", "lease expiry", 30)
            psql(f"update media_job set retry_at = now() - interval '1 second' where id='{job_id}'")
            self._wait_for(lambda: self.job_state(job_id) == "QUEUED", "due retry re-queue", 30)

            # A second worker reaches READY.
            compose("start", "transcoder")
            self._wait_for(lambda: self.job_state(job_id) == "SUCCEEDED", "second worker READY", 180)

            self.assertGreaterEqual(self.attempt_number(job_id), 2)
            self.assertEqual(self.version_state(job_id), "READY")
        finally:
            if os.path.exists(source):
                os.remove(source)

    def test_duplicate_and_late_results(self):
        job_id = self._insert_queued_job()
        claim = self.claim(job_id)
        self.assertEqual(claim["disposition"], "CLAIMED")
        attempt_id = claim["attemptId"]

        publish_result("media.progress.v1", str(uuid.uuid4()), job_id, attempt_id, 2,
                       {"stage": "ENCODING", "percent": 40})
        self._wait_for(lambda: int(psql(
            f"select progress_percent from media_job where id='{job_id}'") or 0) == 40,
            "progress applied", 30)

        # A lower-sequence progress event must not regress the applied progress.
        publish_result("media.progress.v1", str(uuid.uuid4()), job_id, attempt_id, 1,
                       {"stage": "DOWNLOADING", "percent": 5})
        time.sleep(1)
        self.assertEqual(int(psql(f"select progress_percent from media_job where id='{job_id}'")), 40)

        # A terminal failure is applied once; a duplicate event id is not applied twice.
        event_id = str(uuid.uuid4())
        publish_result("media.failed.v1", event_id, job_id, attempt_id, 3,
                       {"code": "SOURCE_INVALID", "summary": "bad source"})
        self._wait_for(lambda: self.job_state(job_id) == "FAILED", "terminal failure", 30)
        publish_result("media.failed.v1", event_id, job_id, attempt_id, 3,
                       {"code": "SOURCE_INVALID", "summary": "bad source"})
        time.sleep(1)
        self.assertEqual(psql(f"select count(*) from inbox_event where event_id='{event_id}'"), "1")

        # A late progress event after the terminal transition must not regress state.
        publish_result("media.progress.v1", str(uuid.uuid4()), job_id, attempt_id, 4,
                       {"stage": "VALIDATING", "percent": 95})
        time.sleep(1)
        self.assertEqual(self.job_state(job_id), "FAILED")

    def test_upload_completion_response_loss(self):
        token = self.register_admin()
        movie_id = self.create_movie(token)
        payload = b"x" * 1024
        upload = self.create_upload(token, movie_id, len(payload))
        self.put_part(token, upload["id"], payload)

        # Simulate the crash between storage completion and the final database transition:
        # complete the multipart upload at storage, then leave the session COMPLETING.
        object_key = psql(f"select object_key from upload_session where id='{upload['id']}'")
        client = s3_client()
        uploads = client.list_multipart_uploads(Bucket=SOURCE_BUCKET).get("Uploads", [])
        upload_id = next((u["UploadId"] for u in uploads if u["Key"] == object_key), None)
        self.assertIsNotNone(upload_id, "no multipart upload found for the session key")
        parts = client.list_parts(Bucket=SOURCE_BUCKET, Key=object_key, UploadId=upload_id)["Parts"]
        client.complete_multipart_upload(
            Bucket=SOURCE_BUCKET, Key=object_key, UploadId=upload_id,
            MultipartUpload={"Parts": [{"PartNumber": p["PartNumber"], "ETag": p["ETag"]}
                                       for p in parts]})
        psql(f"update upload_session set state='COMPLETING' where id='{upload['id']}'")

        first = self.complete_upload(token, upload["id"])
        second = self.complete_upload(token, upload["id"])

        self.assertEqual(first["jobId"], second["jobId"])
        self.assertEqual(psql(f"select count(*) from media_job "
                              f"where id='{first['jobId']}'"), "1")
        self.assertEqual(psql(f"select count(*) from outbox_event "
                              f"where job_id='{first['jobId']}'"), "1")

    # --- scenario setup --------------------------------------------------

    def _insert_queued_job(self):
        movie_id = str(uuid.uuid4())
        version_id = str(uuid.uuid4())
        job_id = str(uuid.uuid4())
        psql_script(f"""
            insert into movie(id, slug, title, synopsis, release_year, maturity_rating,
                              management_mode, lifecycle)
            values ('{movie_id}', 'm{movie_id[:8]}', 'Recovery', 'Recovery', 2026, 'PG',
                    'MANAGED', 'DRAFT');
            insert into media_version(id, movie_id, state, source_key)
            values ('{version_id}', '{movie_id}', 'QUEUED', 'source/{movie_id}/{version_id}/original');
            insert into media_job(id, movie_id, media_version_id, kind, state)
            values ('{job_id}', '{movie_id}', '{version_id}', 'TRANSCODE', 'QUEUED');
        """)
        return job_id


if __name__ == "__main__":
    unittest.main()
