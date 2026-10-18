"""Server tests for the Keep Me Out focus lock. Run: pytest -q"""
import importlib
import os
import sys
import tempfile
import time

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

DEV = {"X-Auth": "dev-key-for-tests"}
ADM = {"X-Admin": "admin-key-for-tests"}


@pytest.fixture()
def client(monkeypatch):
    from fastapi.testclient import TestClient

    tmp = tempfile.mkdtemp()
    monkeypatch.setenv("LOCKOUT_DB", os.path.join(tmp, "gate.db"))
    monkeypatch.setenv("LOCKOUT_DEVICE_KEY", "dev-key-for-tests")
    monkeypatch.setenv("LOCKOUT_ADMIN_KEY", "admin-key-for-tests")
    for m in ("focus", "work", "gate"):
        sys.modules.pop(m, None)
    gate = importlib.import_module("gate")
    with TestClient(gate.app) as c:
        yield c


def start(c, minutes, headers=DEV):
    return c.post("/v1/focus/start", json={"device_id": "primary", "minutes": minutes}, headers=headers)


def test_requires_device_key(client):
    assert start(client, 5, {"X-Auth": "nope"}).status_code == 401
    assert client.get("/v1/focus/state", params={"device_id": "primary"}).status_code == 401


def test_idle_then_start(client):
    r = client.get("/v1/focus/state", params={"device_id": "primary"}, headers=DEV)
    assert r.json()["active"] is False
    r = start(client, 10).json()
    assert r["active"] and 595 <= r["remaining_s"] <= 600


def test_rejects_bad_durations(client):
    assert start(client, 0).status_code == 400
    assert start(client, -5).status_code == 400
    assert start(client, 60 * 24 * 60 + 1).status_code == 400


def test_start_can_only_extend_never_shorten(client):
    long_ = start(client, 120).json()
    shorter = start(client, 5).json()
    assert shorter["ends_at"] == long_["ends_at"]
    longer = start(client, 240).json()
    assert longer["ends_at"] > long_["ends_at"]
    assert longer["started_at"] == long_["started_at"]


def test_phone_cannot_end_it_early(client):
    start(client, 30)
    r = client.post("/v1/focus/end", json={"device_id": "primary"}, headers=DEV)
    assert r.status_code == 401
    assert client.get("/v1/focus/state", params={"device_id": "primary"}, headers=DEV).json()["active"]


def test_emergency_switch_does_not_end_focus(client):
    start(client, 30)
    client.post("/v1/lock/emergency/disable", json={"device_id": "primary"}, headers=DEV)
    assert client.get("/v1/focus/state", params={"device_id": "primary"}, headers=DEV).json()["active"]


def test_admin_key_ends_it(client):
    start(client, 30)
    r = client.post("/v1/focus/end", json={"device_id": "primary"}, headers=ADM)
    assert r.status_code == 200 and r.json()["active"] is False


def test_wrong_admin_key_is_throttled(client):
    start(client, 30)
    codes = [
        client.post("/v1/focus/end", json={"device_id": "primary"}, headers={"X-Admin": "wrong"}).status_code
        for _ in range(7)
    ]
    assert codes[:5] == [401] * 5 and codes[5] == 429
    # even the right key is refused while throttled
    assert client.post("/v1/focus/end", json={"device_id": "primary"}, headers=ADM).status_code == 429


def test_breaks_refused_during_focus(client):
    assert client.post("/v1/break/start", json={"device_id": "primary"}, headers=DEV).status_code == 200
    # end the break state by reporting its full budget, then start focus
    client.post("/v1/break/report", json={"device_id": "primary", "delta_ms": 31 * 60_000}, headers=DEV)
    start(client, 30)
    assert client.post("/v1/break/start", json={"device_id": "primary"}, headers=DEV).status_code == 423


def test_reenable_does_not_cancel_hard_lock(client):
    client.post("/v1/lock/hardlock/start", json={"device_id": "primary", "days": 3}, headers=DEV)
    client.post("/v1/lock/emergency/enable", json={"device_id": "primary"}, headers=DEV)
    st = client.get("/v1/lock/state", params={"device_id": "primary"}, headers=DEV).json()
    assert st["hard_lock_active"] is True


def test_focus_expires_on_server_clock(client, monkeypatch):
    start(client, 1)
    real = time.time
    monkeypatch.setattr(time, "time", lambda: real() + 61)
    assert client.get("/v1/focus/state", params={"device_id": "primary"}, headers=DEV).json()["active"] is False
