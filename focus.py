"""
Keep Me Out — the server-authoritative focus lock.

For a chosen length of time the phone is unusable except for calls and
WhatsApp. Same trust rule as the rest of this repo: the phone may only ASK.

  * The end time is stored here, as a unix timestamp on THIS server's clock.
    Changing the phone's clock, clearing app data or reinstalling the app
    changes nothing — the phone re-syncs the remaining time on every refresh.
  * Starting a lock while one is already running can only EXTEND it. It can
    never shorten it.
  * The focus lock ignores the emergency switch and breaks. Neither can end it.
  * The only way out early is ADMIN_KEY (kept off the phone — write it on
    paper). Wrong keys are throttled so it can't be brute-forced.

    GET  /v1/focus/state   — remaining time (device key)
    POST /v1/focus/start   — start or extend a lock (device key)
    POST /v1/focus/end     — end it early (ADMIN key)
"""

import hmac
import os
import sqlite3
import time
from contextlib import closing

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

router = APIRouter()

# --------------------------------------------------------------------- config
# Read independently from gate.py/work.py (no circular imports); all three
# share the same environment.

DB_PATH = os.getenv("LOCKOUT_DB", "/var/lib/lockout/gate.db")
DEVICE_KEY = os.getenv("LOCKOUT_DEVICE_KEY", "change-me")
ADMIN_KEY = os.getenv("LOCKOUT_ADMIN_KEY", "change-me-too")
MAX_FOCUS_MINUTES = int(os.getenv("LOCKOUT_MAX_FOCUS_MINUTES", str(60 * 24 * 60)))  # 60 days

ADMIN_MAX_FAILS = 5
ADMIN_THROTTLE_SECONDS = 600

SCHEMA = """
CREATE TABLE IF NOT EXISTS focus_lock (
    device_id  TEXT PRIMARY KEY,
    started_at INTEGER NOT NULL,   -- unix seconds
    ends_at    INTEGER NOT NULL    -- unix seconds, server clock
);
"""

# Process-local throttle for wrong admin keys: [consecutive_fails, blocked_until].
_admin_throttle = [0, 0.0]


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=5)
    conn.row_factory = sqlite3.Row
    return conn


def init_schema() -> None:
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    with closing(db()) as conn, conn:
        conn.executescript(SCHEMA)


# --------------------------------------------------------------------- helpers


def _same(a: str, b: str) -> bool:
    """Constant-time string comparison."""
    return hmac.compare_digest(a.encode(), b.encode())


def _check_device(x_auth: str) -> None:
    if not _same(x_auth, DEVICE_KEY):
        raise HTTPException(401, "bad device key")


def _check_admin(x_admin: str) -> None:
    now = time.time()
    if _admin_throttle[1] > now:
        raise HTTPException(429, f"too many wrong keys, try again in {int(_admin_throttle[1] - now) + 1}s")
    if _same(x_admin, ADMIN_KEY):
        _admin_throttle[0] = 0
        return
    _admin_throttle[0] += 1
    if _admin_throttle[0] >= ADMIN_MAX_FAILS:
        _admin_throttle[0] = 0
        _admin_throttle[1] = now + ADMIN_THROTTLE_SECONDS
    raise HTTPException(401, "bad admin key")


class FocusState(BaseModel):
    active: bool
    started_at: int
    ends_at: int
    remaining_s: int
    server_now: int


def _state(row: sqlite3.Row | None, now: int) -> FocusState:
    if row is None or row["ends_at"] <= now:
        return FocusState(active=False, started_at=0, ends_at=0, remaining_s=0, server_now=now)
    return FocusState(
        active=True,
        started_at=row["started_at"],
        ends_at=row["ends_at"],
        remaining_s=row["ends_at"] - now,
        server_now=now,
    )


def _row(conn: sqlite3.Connection, device_id: str) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM focus_lock WHERE device_id = ?", (device_id,)).fetchone()


def focus_active(device_id: str) -> bool:
    """Used by work.py so breaks can't be started during a focus lock."""
    with closing(db()) as conn:
        row = _row(conn, device_id)
    return row is not None and row["ends_at"] > int(time.time())


# ------------------------------------------------------------------- endpoints


class FocusStartRequest(BaseModel):
    device_id: str
    minutes: int


class FocusEndRequest(BaseModel):
    device_id: str


@router.get("/v1/focus/state", response_model=FocusState)
def focus_state(device_id: str, x_auth: str = Header(default="")) -> FocusState:
    _check_device(x_auth)
    with closing(db()) as conn:
        return _state(_row(conn, device_id), int(time.time()))


@router.post("/v1/focus/start", response_model=FocusState)
def focus_start(body: FocusStartRequest, x_auth: str = Header(default="")) -> FocusState:
    """Start a lock, or extend the running one. Never shortens."""
    _check_device(x_auth)
    if not 1 <= body.minutes <= MAX_FOCUS_MINUTES:
        raise HTTPException(400, f"minutes must be 1..{MAX_FOCUS_MINUTES}")

    now = int(time.time())
    requested_end = now + body.minutes * 60
    with closing(db()) as conn, conn:
        # BEGIN IMMEDIATE so two concurrent starts can't race each other.
        conn.execute("BEGIN IMMEDIATE")
        row = _row(conn, body.device_id)
        if row is not None and row["ends_at"] > now:
            started_at = row["started_at"]
            ends_at = max(row["ends_at"], requested_end)
        else:
            started_at, ends_at = now, requested_end
        conn.execute(
            """
            INSERT INTO focus_lock (device_id, started_at, ends_at) VALUES (?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                started_at = excluded.started_at,
                ends_at    = excluded.ends_at
            """,
            (body.device_id, started_at, ends_at),
        )
        return _state(_row(conn, body.device_id), now)


@router.post("/v1/focus/end", response_model=FocusState)
def focus_end(body: FocusEndRequest, x_admin: str = Header(default="")) -> FocusState:
    """Early exit. Needs ADMIN_KEY, which is deliberately not stored on the phone."""
    _check_admin(x_admin)
    now = int(time.time())
    with closing(db()) as conn, conn:
        conn.execute(
            "UPDATE focus_lock SET ends_at = ? WHERE device_id = ? AND ends_at > ?",
            (now, body.device_id, now),
        )
        return _state(_row(conn, body.device_id), now)
