"""Regression attacks use temporary databases and synthetic credentials only."""
import asyncio
import importlib
import time
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import pytest

from test_focus import ADM, DEV, client, start


def test_admin_budget_shared_across_routes_and_device_ids(client):
    assert start(client, 30).status_code == 200
    bad = {**DEV, "X-Admin": "wrong"}
    for i in range(5):
        response = client.get("/v1/status", params={"device_id": f"guess-{i}"}, headers=bad)
        assert response.status_code == 401
    for path, params in [
        ("/v1/override", {"device_id": "primary"}),
        ("/v1/focus/end", None),
    ]:
        response = client.post(path, params=params, json={"device_id": "primary"}, headers=ADM)
        assert response.status_code == 429
        assert 0 < int(response.headers["retry-after"]) <= 600
    assert client.get("/v1/focus/state", params={"device_id": "primary"}, headers=DEV).json()["active"]


def test_admin_cooldown_persists_and_cannot_be_extended(client, monkeypatch):
    import server_auth
    now = int(time.time())
    monkeypatch.setattr(time, "time", lambda: now)
    for _ in range(5):
        assert client.get("/v1/status", params={"device_id": "primary"}, headers=DEV).status_code == 401
    importlib.reload(server_auth)
    now += 599
    response = client.get("/v1/status", params={"device_id": "primary"}, headers=DEV)
    assert response.status_code == 429 and response.headers["retry-after"] == "1"
    now += 2
    # A valid key reaches the handler again (the device doesn't exist).
    assert client.get("/v1/status", params={"device_id": "primary"}, headers=ADM).status_code == 404


def test_admin_parallel_guesses_share_one_atomic_budget(client):
    barrier = Barrier(8)
    def guess(i):
        barrier.wait()
        return client.get("/v1/status", params={"device_id": str(i)}, headers=DEV).status_code
    with ThreadPoolExecutor(max_workers=8) as pool:
        codes = list(pool.map(guess, range(8)))
    assert codes.count(401) == 5 and codes.count(429) == 3


def test_old_throttle_is_preserved_on_upgrade(client):
    import focus
    import sqlite3
    import server_auth
    with sqlite3.connect(focus.DB_PATH) as conn:
        conn.execute("DROP TABLE admin_attempt")
        conn.execute("INSERT INTO focus_admin_attempt VALUES ('old',5,?)", (int(time.time()) + 500,))
    server_auth.init_admin_schema(focus.DB_PATH)
    assert client.get("/v1/status", params={"device_id": "primary"}, headers=ADM).status_code == 429


def test_parallel_usage_reports_cannot_reduce_recorded_usage(client):
    barrier = Barrier(8)
    def report(i):
        barrier.wait()
        return client.post("/v1/check", json={"device_id": "primary", "used_ms": i * 1000}, headers=DEV).status_code
    with ThreadPoolExecutor(max_workers=8) as pool:
        assert list(pool.map(report, range(8))) == [200] * 8
    assert client.post("/v1/check", json={"device_id": "primary", "used_ms": 0}, headers=DEV).json()["used_ms"] == 7000


def test_oversized_integer_returns_validation_error_not_server_crash(client):
    assert client.post("/v1/check", json={"device_id": "primary", "used_ms": 2**100}, headers=DEV).status_code == 422


def hardlock(client, days=10):
    return client.post("/v1/lock/hardlock/start", json={"device_id": "primary", "days": days}, headers=DEV)


def test_repeated_hardlock_cannot_shorten_or_refund_break(client):
    assert hardlock(client).status_code == 200
    assert client.post("/v1/break/start", json={"device_id": "primary"}, headers=DEV).json()["active_break"]
    client.post("/v1/break/report", json={"device_id": "primary", "delta_ms": 5 * 60_000}, headers=DEV)
    repeated = hardlock(client, 1).json()
    assert repeated["hard_lock_days_remaining"] == 10
    assert repeated["breaks_used_today"] == 1 and repeated["active_break_remaining_ms"] == 25 * 60_000
    client.post("/v1/break/report", json={"device_id": "primary", "delta_ms": 30 * 60_000}, headers=DEV)
    hardlock(client, 20)
    assert client.post("/v1/break/start", json={"device_id": "primary"}, headers=DEV).status_code == 429


def test_focus_and_break_race_never_leaves_a_live_break(client):
    barrier = Barrier(2)
    def begin_focus():
        barrier.wait()
        return start(client, 30).status_code
    def begin_break():
        barrier.wait()
        return client.post("/v1/break/start", json={"device_id": "primary"}, headers=DEV).status_code
    with ThreadPoolExecutor(max_workers=2) as pool:
        one, two = pool.submit(begin_focus), pool.submit(begin_break)
        assert one.result() == 200 and two.result() in (200, 423)
    assert client.get("/v1/lock/state", params={"device_id": "primary"}, headers=DEV).json()["active_break"] is False


@pytest.mark.parametrize("change", ["focus", "pause", "consume"])
def test_delayed_photo_cannot_override_new_protection_or_quota(client, monkeypatch, change):
    import work
    import gemini_verify
    hardlock(client)
    def verify(*args):
        if change == "focus":
            assert start(client, 30).status_code == 200
        elif change == "pause":
            assert client.post("/v1/lock/emergency/disable", json={"device_id": "primary"}, headers=DEV).status_code == 200
        else:
            assert client.post("/v1/break/start", json={"device_id": "primary"}, headers=DEV).status_code == 200
            client.post("/v1/break/report", json={"device_id": "primary", "delta_ms": 31 * 60_000}, headers=DEV)
        return gemini_verify.VerifyResult(accepted=True, confidence=1.0, reasoning="synthetic")
    monkeypatch.setattr(work.gemini_verify, "verify_meal", verify)
    response = client.post("/v1/break/claim", headers=DEV, data={"device_id": "primary"},
                           files={"file": ("test.jpg", b"synthetic", "image/jpeg")})
    assert response.status_code == {"focus": 423, "pause": 409, "consume": 200}[change]
    if change == "consume":
        assert response.json()["accepted"] is False
    assert client.get("/v1/lock/state", params={"device_id": "primary"}, headers=DEV).json()["active_break"] is False


def test_parallel_photo_claims_grant_one_break(client, monkeypatch):
    import work
    import gemini_verify
    hardlock(client)
    barrier = Barrier(2)
    def verify(*args):
        barrier.wait(timeout=10)
        return gemini_verify.VerifyResult(accepted=True, confidence=1.0, reasoning="synthetic")
    monkeypatch.setattr(work.gemini_verify, "verify_meal", verify)
    def claim(_):
        return client.post("/v1/break/claim", headers=DEV, data={"device_id": "primary"},
                           files={"file": ("test.jpg", b"synthetic", "image/jpeg")})
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(claim, range(2)))
    assert all(r.status_code == 200 and r.json()["accepted"] for r in responses)
    state = client.get("/v1/lock/state", params={"device_id": "primary"}, headers=DEV).json()
    assert state["breaks_used_today"] == 1 and state["active_break"]


def raw_request(app, path, chunks, headers=()):
    """Exercise ASGI chunking directly; don't let an HTTP test client buffer it."""
    sent, reads = [], []
    async def receive():
        index = len(reads)
        reads.append(index)
        return {"type": "http.request", "body": chunks[index], "more_body": index + 1 < len(chunks)}
    async def send(message):
        sent.append(message)
    scope = {
        "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
        "method": "POST", "scheme": "https", "path": path, "raw_path": path.encode(),
        "query_string": b"", "root_path": "", "headers": list(headers),
        "client": ("127.0.0.1", 1234), "server": ("test", 443),
    }
    asyncio.run(app(scope, receive, send))
    return next(item["status"] for item in sent if item["type"] == "http.response.start"), reads


def test_unauthenticated_upload_is_rejected_before_any_body_read(client):
    import gate
    code, reads = raw_request(gate.app, "/v1/break/claim", [b"unused large upload"])
    assert code == 401 and reads == []


@pytest.mark.parametrize("content_length", [None, b"1"])
def test_streamed_or_lying_json_body_is_bounded(client, content_length):
    import gate
    import server_transport
    headers = [(b"x-auth", DEV["X-Auth"].encode()), (b"content-type", b"application/json")]
    if content_length is not None:
        headers.append((b"content-length", content_length))
    code, reads = raw_request(gate.app, "/v1/check", [b" " * server_transport.JSON_LIMIT, b"x", b"unread"], headers)
    assert code == 413 and len(reads) == 2


def test_streamed_multipart_is_bounded_before_provider(client, monkeypatch):
    import gate
    import server_transport
    monkeypatch.setattr(server_transport, "UPLOAD_LIMIT", 100)
    headers = [
        (b"x-auth", DEV["X-Auth"].encode()),
        (b"content-type", b"multipart/form-data; boundary=test"),
    ]
    prefix = b'--test\r\nContent-Disposition: form-data; name="file"; filename="x.jpg"\r\n\r\n'
    code, reads = raw_request(gate.app, "/v1/break/claim", [prefix, b"x" * 101, b"unread"], headers)
    assert code == 413 and len(reads) == 2


def test_oversized_declared_body_rejected_before_read(client):
    import gate
    code, reads = raw_request(gate.app, "/v1/check", [b"unused"], [
        (b"x-auth", DEV["X-Auth"].encode()), (b"content-length", b"1000000000"),
    ])
    assert code == 413 and reads == []


def test_duplicate_credentials_rejected_and_responses_not_cached(client):
    response = client.post("/v1/check", json={"device_id": "primary", "used_ms": 0},
                           headers=[("X-Auth", DEV["X-Auth"]), ("X-Auth", "other")])
    assert response.status_code == 400
    response = client.get("/v1/focus/state", params={"device_id": "primary"}, headers=DEV)
    assert response.status_code == 200 and response.headers["cache-control"] == "no-store"
