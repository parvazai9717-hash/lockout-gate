"""Exercise real public and legacy endpoints with synthetic identities only."""
import importlib
import sqlite3
import time
from test_focus import client, DEV, ADM, start as personal_start

ID = "12345678-1234-4234-8234-123456789abc"
SECRET = "public-device-secret-for-tests-only-123456789"
KEY = f"LG1.{ID}.0123456789abcdef0123456789abcdef"
AUTH = {"X-Auth": SECRET}


def enroll(c, device_id=ID, secret=SECRET, key=KEY):
    return c.post("/v1/public/enroll", json={"device_id": device_id, "device_secret": secret, "recovery_key": key})


def start(c, minutes=10, headers=AUTH):
    return c.post("/v1/public/focus/start", json={"device_id": ID, "minutes": minutes}, headers=headers)


def state(c, headers=AUTH, device_id=ID):
    return c.post("/v1/public/focus/state", json={"device_id": device_id}, headers=headers)


def test_setup_and_server_clock_without_personal_key(client, monkeypatch):
    assert enroll(client).status_code == 200
    response = start(client, 1)
    assert response.status_code == 200
    lock = response.json()
    assert lock["active"] and lock["remaining_s"] == 60
    assert response.headers["cache-control"] == "no-store"
    real = time.time
    monkeypatch.setattr(time, "time", lambda: real() + 61)
    assert not state(client).json()["active"]


def test_public_and_personal_credential_domains_are_separate(client):
    assert enroll(client).status_code == 200
    assert state(client, {}).status_code == 401
    assert state(client, DEV).status_code == 401
    assert client.get("/v1/focus/state", params={"device_id": "primary"}, headers=AUTH).status_code == 401
    assert personal_start(client, 2).status_code == 200
    assert start(client, 3).status_code == 200
    assert client.get("/v1/focus/state", params={"device_id": "primary"}, headers=DEV).json()["remaining_s"] <= 120


def test_another_account_cannot_read_modify_or_delete_a_lock(client):
    assert enroll(client).status_code == 200
    other = "87654321-4321-4321-8321-cba987654321"
    other_secret = "another-public-install-secret-for-tests-123456"
    assert enroll(client, other, other_secret, "different-recovery-secret-for-tests-123456").status_code == 200
    assert start(client).status_code == 200
    headers = {"X-Auth": other_secret}
    assert state(client, headers).status_code == 401
    assert start(client, headers=headers).status_code == 401
    assert client.post("/v1/public/account/delete", json={"device_id": ID}, headers=headers).status_code == 401


def test_repeated_start_cannot_shorten_and_delete_cannot_bypass(client):
    enroll(client)
    original = start(client, 60).json()
    assert start(client, 1).json()["ends_at"] == original["ends_at"]
    assert client.post("/v1/public/account/delete", json={"device_id": ID}, headers=AUTH).status_code == 423
    assert enroll(client).status_code == 423
    assert state(client).json()["ends_at"] == original["ends_at"]


def test_only_own_recovery_key_can_end_early(client):
    enroll(client)
    start(client)
    body = {"device_id": ID}
    assert client.post("/v1/public/focus/end", json=body, headers=AUTH).status_code == 401
    assert client.post("/v1/public/focus/end", json=body, headers={**AUTH, "X-Admin": ADM["X-Admin"]}).status_code == 401
    response = client.post("/v1/public/focus/end", json=body, headers={**AUTH, "X-Admin": KEY})
    assert response.status_code == 200 and not response.json()["active"]


def test_bad_key_budget_persists_across_end_and_recovery_and_restart(client):
    enroll(client)
    start(client)
    for _ in range(5):
        assert client.post("/v1/public/focus/end", json={"device_id": ID}, headers=AUTH).status_code == 401
    import public_focus
    importlib.reload(public_focus)
    response = client.post("/v1/public/recover", json={"device_id": ID, "device_secret": "new-install-secret-for-tests-123456789", "recovery_key": KEY})
    assert response.status_code == 429
    assert state(client).json()["active"]


def test_recovery_rotates_only_device_access_and_keeps_exact_lock(client):
    enroll(client)
    original = start(client).json()
    new_secret = "recovered-device-credential-for-tests-123456789"
    recovered = client.post("/v1/public/recover", json={"device_id": ID, "device_secret": new_secret, "recovery_key": KEY})
    assert recovered.status_code == 200
    assert recovered.json()["ends_at"] == original["ends_at"]
    assert state(client).status_code == 401
    assert state(client, {"X-Auth": new_secret}).json()["ends_at"] == original["ends_at"]


def test_delete_erases_only_own_public_account(client):
    enroll(client)
    personal = personal_start(client, 10).json()
    assert client.post("/v1/public/account/delete", json={"device_id": ID}, headers=AUTH).json() == {"deleted": True}
    assert state(client).status_code == 401
    assert client.get("/v1/focus/state", params={"device_id": "primary"}, headers=DEV).json()["ends_at"] == personal["ends_at"]
    import focus
    with sqlite3.connect(focus.DB_PATH) as c:
        assert c.execute("SELECT COUNT(*) FROM public_focus_account").fetchone()[0] == 0


def test_setup_rate_limit_and_capacity_bound_storage(client, monkeypatch):
    monkeypatch.setenv("LOCKOUT_PUBLIC_MAX_ACCOUNTS", "1")
    assert enroll(client).status_code == 200
    assert enroll(client, "87654321-4321-4321-8321-cba987654321", "another-public-install-secret-for-tests-123456").status_code == 503
    for _ in range(18):
        assert enroll(client).status_code == 200
    assert enroll(client).status_code == 429


def test_public_routes_still_enforce_body_and_header_limits(client):
    assert client.post("/v1/public/enroll", content=b"x" * (65 * 1024), headers={"Content-Type": "application/json"}).status_code == 413
    assert client.post("/v1/public/enroll", headers=[("X-Auth", "a"), ("X-Auth", "b")], json={}).status_code == 400
    assert client.post("/v1/public/enroll", json={"device_id": "short", "device_secret": SECRET, "recovery_key": KEY}).status_code == 422


def test_database_keeps_hashes_and_snapshot_survives_startup(client):
    enroll(client)
    import gate
    gate.backup_once(".before-public-focus-v1.bak", "Pre-public-focus")
    original = start(client).json()
    gate.init_db()
    assert state(client).json()["ends_at"] == original["ends_at"]
    with sqlite3.connect(gate.DB_PATH) as c:
        row = c.execute("SELECT secret_hash,recovery_hash FROM public_focus_account").fetchone()
        assert row[0] != SECRET and row[1] != KEY
        assert all(len(value) == 64 for value in row)
