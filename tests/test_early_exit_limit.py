"""Server-owned monthly allowance, using only disposable test identities."""
import importlib
import sqlite3
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from test_focus import client, DEV, ADM
from test_public_focus import ID, SECRET, KEY, AUTH, enroll


def api(c, public):
    prefix = "/v1/public/focus" if public else "/v1/focus"
    device_id = ID if public else "primary"
    auth = AUTH if public else DEV
    admin = {**AUTH, "X-Admin": KEY} if public else ADM
    if public:
        assert enroll(c).status_code == 200
    def state():
        if public:
            return c.post(prefix + "/state", json={"device_id": device_id}, headers=auth)
        return c.get(prefix + "/state", params={"device_id": device_id}, headers=auth)
    def start():
        return c.post(prefix + "/start", json={"device_id": device_id, "minutes": 10}, headers=auth)
    def end(headers=None):
        return c.post(prefix + "/end", json={"device_id": device_id}, headers=admin if headers is None else headers)
    return start, end, state, auth


@pytest.mark.parametrize("public", [False, True])
def test_two_successful_exits_then_third_lock_remains_active(client, public):
    start, end, state, _ = api(client, public)
    assert state().json()["early_exits_remaining"] == 2
    for remaining in (1, 0):
        assert start().json()["active"]
        result = end()
        assert result.status_code == 200 and not result.json()["active"]
        assert result.json()["early_exits_remaining"] == remaining
        # Lost-response retry must not consume another slot.
        assert end().json()["early_exits_remaining"] == remaining
    lock = start().json()
    assert end().status_code == 409
    assert state().json()["active"]
    assert state().json()["ends_at"] == lock["ends_at"]


@pytest.mark.parametrize("public", [False, True])
def test_wrong_keys_and_expired_locks_do_not_spend_allowance(client, public, monkeypatch):
    start, end, state, auth = api(client, public)
    start()
    assert end({**auth, "X-Admin": "wrong-key"}).status_code == 401
    assert state().json()["early_exits_remaining"] == 2
    real = time.time()
    monkeypatch.setattr(time, "time", lambda: real + 601)
    assert not state().json()["active"]
    assert end().json()["early_exits_remaining"] == 2


@pytest.mark.parametrize("public", [False, True])
def test_reset_uses_server_calendar_month_and_year_boundary(client, public, monkeypatch):
    now = [int(datetime(2026, 12, 31, 23, 55, tzinfo=ZoneInfo("Asia/Karachi")).timestamp())]
    monkeypatch.setattr(time, "time", lambda: now[0])
    start, end, state, _ = api(client, public)
    for _ in range(2):
        start()
        assert end().status_code == 200
    original = start().json()
    assert end().status_code == 409
    reset = int(datetime(2027, 1, 1, tzinfo=ZoneInfo("Asia/Karachi")).timestamp())
    assert state().json()["early_exit_resets_at"] == reset
    assert state().json()["early_exit_timezone"] == "Asia/Karachi"
    now[0] = reset
    assert state().json()["early_exits_remaining"] == 2
    assert state().json()["ends_at"] == original["ends_at"]
    assert end().json()["early_exits_remaining"] == 1


def test_recovery_reenrollment_and_restart_preserve_public_allowance(client):
    start, end, state, _ = api(client, True)
    start()
    assert end().json()["early_exits_remaining"] == 1
    assert enroll(client).json()["early_exits_remaining"] == 1
    lock = start().json()
    import focus
    importlib.reload(focus)
    new_secret = SECRET + "rotated"
    recovered = client.post("/v1/public/recover", json={"device_id": ID,
        "device_secret": new_secret, "recovery_key": KEY})
    assert recovered.json()["early_exits_remaining"] == 1
    assert recovered.json()["ends_at"] == lock["ends_at"]
    assert state().status_code == 401
    ended = client.post("/v1/public/focus/end", json={"device_id": ID},
        headers={"X-Auth": new_secret, "X-Admin": KEY})
    assert ended.json()["early_exits_remaining"] == 0
    assert client.post("/v1/public/account/delete", json={"device_id": ID}, headers={"X-Auth": new_secret}).status_code == 200
    with sqlite3.connect(focus.DB_PATH) as c:
        assert c.execute("SELECT COUNT(*) FROM focus_early_exit WHERE scope='public'").fetchone()[0] == 0


@pytest.mark.parametrize("public", [False, True])
def test_simultaneous_retries_charge_one_exit(client, public):
    start, end, state, _ = api(client, public)
    start()
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: end().status_code, range(2)))
    assert results == [200, 200]
    assert state().json()["early_exits_remaining"] == 1


def test_accounts_and_personal_public_domains_have_separate_allowances(client):
    personal_start, personal_end, personal_state, _ = api(client, False)
    public_start, public_end, public_state, _ = api(client, True)
    for _ in range(2):
        personal_start()
        assert personal_end().status_code == 200
    assert personal_state().json()["early_exits_remaining"] == 0
    assert public_state().json()["early_exits_remaining"] == 2
    public_start()
    assert public_end().json()["early_exits_remaining"] == 1
    assert enroll(client, "another-disposable-account", SECRET + "other", KEY + "other").json()["early_exits_remaining"] == 2


def test_new_snapshot_preserves_existing_data_and_quota(client):
    start, end, state, _ = api(client, True)
    start()
    end()
    lock = start().json()
    import gate
    gate.init_db()
    assert state().json()["ends_at"] == lock["ends_at"]
    assert state().json()["early_exits_remaining"] == 1
    with sqlite3.connect(gate.DB_PATH + ".before-early-exits-v1.bak") as snapshot:
        assert snapshot.execute("PRAGMA quick_check").fetchone()[0] == "ok"
