"""
App-lock layer: blocks a fixed set of entertainment apps by default and
gives back short, real-usage-tracked breaks — no task, no "prove you worked."

    Normal mode (always on, no button needed):
        3 breaks/day, 30 min of real screen time each, no photo required.
    Hard Lock mode (opt-in, POST /v1/lock/hardlock/start):
        blocks the apps hard for N days; only 1 break/day, and that one
        break requires a Gemini-verified photo of yourself eating.
    Emergency override:
        a true on/off switch (POST /v1/lock/emergency/disable|enable).
        Disabling is capped at once per calendar month; re-enabling is not.

Breaks are NOT a wall-clock countdown. Starting one just opens the door;
the phone reports real foreground usage of the watched apps as it happens
(POST /v1/break/report, monotonic, same anti-cheat pattern as gate.py's
/v1/check), and the break only expires once 30 real minutes are used up —
walking away and coming back later doesn't burn it down.

    GET  /v1/lock/state            — current lock/break/hard-lock status.
    POST /v1/break/start           — start a break (no photo); 1/day during a hard lock.
    POST /v1/break/claim           — legacy photo-verified hard-lock break.
    POST /v1/break/report          — report real usage ms for the active break.
    POST /v1/lock/hardlock/start   — begin an N-day hard lock.
    POST /v1/lock/emergency/disable — turn off all blocking (max 1x/month).
    POST /v1/lock/emergency/enable  — turn blocking back on (unlimited).

Every request carries X-Auth (the app-wide DEVICE_KEY) and X-Device-Secret
(a random per-install secret). The server stores a hash of the secret the
first time it sees a device, so knowing another phone's device_id is not
enough to change its lock state.
"""

import hashlib
import hmac
import os
import sqlite3
import time
from contextlib import closing
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import APIRouter, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel

import gemini_verify

router = APIRouter()

# --------------------------------------------------------------------- config
# Read independently from gate.py to avoid a circular import; both processes
# share the same environment, so the values are identical in practice.

DB_PATH = os.getenv("LOCKOUT_DB", "/var/lib/lockout/gate.db")
DEVICE_KEY = os.getenv("LOCKOUT_DEVICE_KEY", "change-me")
PROOF_DIR = os.getenv("LOCKOUT_PROOF_DIR", os.path.join(os.path.dirname(DB_PATH) or ".", "proofs"))
MAX_PROOF_BYTES = 20 * 1024 * 1024  # inline Gemini upload cap
TZ = ZoneInfo(os.getenv("LOCKOUT_TZ", "Asia/Karachi"))
BREAK_MS = int(os.getenv("LOCKOUT_BREAK_MINUTES", "30")) * 60_000
MIN_BREAK_MINUTES = 5
MAX_BREAK_MINUTES = int(os.getenv("LOCKOUT_MAX_BREAK_MINUTES", "45"))
MAX_BREAKS_PER_DAY = int(os.getenv("LOCKOUT_MAX_BREAKS_PER_DAY", "3"))
MAX_HARDLOCK_BREAKS_PER_DAY = int(os.getenv("LOCKOUT_MAX_HARDLOCK_BREAKS_PER_DAY", "1"))
MAX_HARDLOCK_DAYS = 90

SCHEMA = """
CREATE TABLE IF NOT EXISTS lock_state (
    device_id                TEXT PRIMARY KEY,
    enabled                  INTEGER NOT NULL DEFAULT 1,
    hard_lock_until          TEXT,
    emergency_month          TEXT,
    active_break_started_at  INTEGER,
    active_break_used_ms     INTEGER NOT NULL DEFAULT 0,
    active_break_limit_ms    INTEGER,
    device_secret_hash       TEXT
);

CREATE TABLE IF NOT EXISTS daily_breaks (
    device_id   TEXT NOT NULL,
    day         TEXT NOT NULL,
    breaks_used INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, day)
);
"""


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=5)
    conn.row_factory = sqlite3.Row
    return conn


def today() -> str:
    """Server-local calendar date — same rollover anchor gate.py uses."""
    return datetime.now(TZ).strftime("%Y-%m-%d")


def current_month() -> str:
    return datetime.now(TZ).strftime("%Y-%m")


# Columns added after the first release; ALTERed into existing databases.
_MIGRATIONS = {
    "active_break_limit_ms": "ALTER TABLE lock_state ADD COLUMN active_break_limit_ms INTEGER",
    "device_secret_hash": "ALTER TABLE lock_state ADD COLUMN device_secret_hash TEXT",
}


def init_schema() -> None:
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    os.makedirs(PROOF_DIR, exist_ok=True)
    with closing(db()) as conn, conn:
        conn.executescript(SCHEMA)
        existing = {r["name"] for r in conn.execute("PRAGMA table_info(lock_state)")}
        for column, ddl in _MIGRATIONS.items():
            if column not in existing:
                conn.execute(ddl)


def _check_auth(x_auth: str) -> None:
    if not hmac.compare_digest(x_auth.encode(), DEVICE_KEY.encode()):
        raise HTTPException(401, "bad device key")


def _hash_secret(secret: str) -> str:
    return hashlib.sha256(secret.encode()).hexdigest()


def _get_or_create_lock_row(conn: sqlite3.Connection, device_id: str) -> sqlite3.Row:
    conn.execute(
        "INSERT INTO lock_state (device_id) VALUES (?) ON CONFLICT(device_id) DO NOTHING",
        (device_id,),
    )
    return conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (device_id,)).fetchone()


def _load_device(conn: sqlite3.Connection, device_id: str, secret: str) -> sqlite3.Row:
    """
    Fetch (or create) this device's row and check its per-install secret.
    The first secret seen for a device is remembered; after that, requests
    must present the same one. Devices from before secrets existed (no hash
    stored, no secret sent) keep working until they send one.
    """
    if not 1 <= len(device_id) <= 100:
        raise HTTPException(400, "invalid device_id")
    row = _get_or_create_lock_row(conn, device_id)
    stored = row["device_secret_hash"]
    if stored is None:
        if secret:
            conn.execute(
                "UPDATE lock_state SET device_secret_hash = ? WHERE device_id = ?",
                (_hash_secret(secret), device_id),
            )
            row = conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (device_id,)).fetchone()
        return row
    if not secret or not hmac.compare_digest(stored, _hash_secret(secret)):
        raise HTTPException(401, "device secret mismatch")
    return row


def _break_limit_ms(minutes: int | None) -> int:
    if minutes is None:
        return BREAK_MS
    if not MIN_BREAK_MINUTES <= minutes <= MAX_BREAK_MINUTES:
        raise HTTPException(400, f"minutes must be {MIN_BREAK_MINUTES}..{MAX_BREAK_MINUTES}")
    return minutes * 60_000


def _row_break_limit_ms(row: sqlite3.Row) -> int:
    return row["active_break_limit_ms"] or BREAK_MS


def _breaks_used_today(conn: sqlite3.Connection, device_id: str) -> int:
    row = conn.execute(
        "SELECT breaks_used FROM daily_breaks WHERE device_id = ? AND day = ?",
        (device_id, today()),
    ).fetchone()
    return row["breaks_used"] if row else 0


def _increment_breaks_used(conn: sqlite3.Connection, device_id: str) -> None:
    conn.execute(
        """
        INSERT INTO daily_breaks (device_id, day, breaks_used) VALUES (?, ?, 1)
        ON CONFLICT(device_id, day) DO UPDATE SET breaks_used = breaks_used + 1
        """,
        (device_id, today()),
    )


def _hard_lock_active(row: sqlite3.Row) -> bool:
    until = row["hard_lock_until"]
    return until is not None and today() <= until


def _hard_lock_days_remaining(row: sqlite3.Row) -> int:
    until = row["hard_lock_until"]
    if until is None:
        return 0
    remaining = (date.fromisoformat(until) - date.fromisoformat(today())).days
    return max(0, remaining)


def _build_state(conn: sqlite3.Connection, device_id: str, row: sqlite3.Row) -> "LockState":
    hard_lock_active = _hard_lock_active(row)
    cap = MAX_HARDLOCK_BREAKS_PER_DAY if hard_lock_active else MAX_BREAKS_PER_DAY
    breaks_used = _breaks_used_today(conn, device_id)
    enabled = bool(row["enabled"])
    active_break = enabled and row["active_break_started_at"] is not None
    remaining_ms = max(0, _row_break_limit_ms(row) - row["active_break_used_ms"]) if active_break else 0
    return LockState(
        enabled=enabled,
        locked=enabled and not active_break,
        hard_lock_active=hard_lock_active,
        hard_lock_days_remaining=_hard_lock_days_remaining(row),
        breaks_used_today=breaks_used,
        breaks_remaining_today=max(0, cap - breaks_used),
        active_break=active_break,
        emergency_available=row["emergency_month"] != current_month(),
        active_break_remaining_ms=remaining_ms,
    )


# --------------------------------------------------------------------- schemas


class LockState(BaseModel):
    enabled: bool
    locked: bool
    hard_lock_active: bool
    hard_lock_days_remaining: int
    breaks_used_today: int
    breaks_remaining_today: int
    active_break: bool
    emergency_available: bool
    active_break_remaining_ms: int = 0


class DeviceRequest(BaseModel):
    device_id: str
    minutes: int | None = None


class BreakReportRequest(BaseModel):
    device_id: str
    delta_ms: int


class HardLockStartRequest(BaseModel):
    device_id: str
    days: int


class BreakClaimResult(LockState):
    accepted: bool
    confidence: float
    reasoning: str


# ------------------------------------------------------------------- endpoints


@router.get("/v1/lock/state", response_model=LockState)
def lock_state(
    device_id: str,
    x_auth: str = Header(default=""),
    x_device_secret: str = Header(default=""),
) -> LockState:
    _check_auth(x_auth)
    with closing(db()) as conn, conn:
        row = _load_device(conn, device_id, x_device_secret)
        return _build_state(conn, device_id, row)


@router.post("/v1/break/start", response_model=LockState)
def break_start(
    body: DeviceRequest,
    x_auth: str = Header(default=""),
    x_device_secret: str = Header(default=""),
) -> LockState:
    """Start a break — no photo needed. During a hard lock the daily cap drops to 1."""
    _check_auth(x_auth)
    limit_ms = _break_limit_ms(body.minutes)
    with closing(db()) as conn, conn:
        row = _load_device(conn, body.device_id, x_device_secret)
        if not row["enabled"]:
            return _build_state(conn, body.device_id, row)
        if row["active_break_started_at"] is not None:
            return _build_state(conn, body.device_id, row)  # already active, idempotent

        cap = MAX_HARDLOCK_BREAKS_PER_DAY if _hard_lock_active(row) else MAX_BREAKS_PER_DAY
        breaks_used = _breaks_used_today(conn, body.device_id)
        if breaks_used >= cap:
            raise HTTPException(429, f"daily break limit reached ({breaks_used}/{cap})")

        now = int(time.time())
        conn.execute(
            """
            UPDATE lock_state SET active_break_started_at = ?, active_break_used_ms = 0,
                                  active_break_limit_ms = ?
            WHERE device_id = ?
            """,
            (now, limit_ms, body.device_id),
        )
        _increment_breaks_used(conn, body.device_id)
        row = conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (body.device_id,)).fetchone()
        return _build_state(conn, body.device_id, row)


@router.post("/v1/break/claim", response_model=BreakClaimResult)
async def break_claim(
    device_id: str = Form(...),
    file: UploadFile = File(...),
    minutes: int | None = Form(default=None),
    x_auth: str = Header(default=""),
    x_device_secret: str = Header(default=""),
) -> BreakClaimResult:
    """Start a hard-lock break — requires a Gemini-verified eating photo."""
    _check_auth(x_auth)
    limit_ms = _break_limit_ms(minutes)

    with closing(db()) as conn, conn:
        row = _load_device(conn, device_id, x_device_secret)
        if not _hard_lock_active(row):
            raise HTTPException(400, "no hard lock active — use /v1/break/start instead")

        if row["active_break_started_at"] is not None:
            state = _build_state(conn, device_id, row)
            return BreakClaimResult(accepted=True, confidence=1.0, reasoning="a break is already active", **state.model_dump())

        breaks_used = _breaks_used_today(conn, device_id)
        if breaks_used >= MAX_HARDLOCK_BREAKS_PER_DAY:
            state = _build_state(conn, device_id, row)
            return BreakClaimResult(
                accepted=False,
                confidence=0.0,
                reasoning=f"rejected: daily break limit reached ({breaks_used}/{MAX_HARDLOCK_BREAKS_PER_DAY})",
                **state.model_dump(),
            )

    content_type = file.content_type or "application/octet-stream"
    if not content_type.startswith("image/"):
        raise HTTPException(400, f"eating break proof must be a photo, got: {content_type}")

    media_bytes = await file.read()
    if len(media_bytes) > MAX_PROOF_BYTES:
        raise HTTPException(413, "photo too large (20MB limit)")

    result = gemini_verify.verify_meal(media_bytes, content_type)

    with closing(db()) as conn, conn:
        if result.accepted:
            now = int(time.time())
            conn.execute(
                """
                UPDATE lock_state SET active_break_started_at = ?, active_break_used_ms = 0,
                                      active_break_limit_ms = ?
                WHERE device_id = ?
                """,
                (now, limit_ms, device_id),
            )
            _increment_breaks_used(conn, device_id)
        row = conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (device_id,)).fetchone()
        state = _build_state(conn, device_id, row)

    return BreakClaimResult(
        accepted=result.accepted,
        confidence=result.confidence,
        reasoning=result.reasoning,
        **state.model_dump(),
    )


@router.post("/v1/break/report", response_model=LockState)
def break_report(
    body: BreakReportRequest,
    x_auth: str = Header(default=""),
    x_device_secret: str = Header(default=""),
) -> LockState:
    """
    Phone reports real foreground-usage ms accumulated since its last report
    while a break is active. Monotonic-safe: a non-positive delta is ignored
    rather than allowed to claw back used time.
    """
    _check_auth(x_auth)
    with closing(db()) as conn, conn:
        row = _load_device(conn, body.device_id, x_device_secret)
        if row["active_break_started_at"] is None:
            return _build_state(conn, body.device_id, row)

        new_used = row["active_break_used_ms"] + max(0, body.delta_ms)
        if new_used >= _row_break_limit_ms(row):
            conn.execute(
                "UPDATE lock_state SET active_break_started_at = NULL, active_break_used_ms = 0 WHERE device_id = ?",
                (body.device_id,),
            )
        else:
            conn.execute(
                "UPDATE lock_state SET active_break_used_ms = ? WHERE device_id = ?",
                (new_used, body.device_id),
            )
        row = conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (body.device_id,)).fetchone()
        return _build_state(conn, body.device_id, row)


@router.post("/v1/lock/hardlock/start", response_model=LockState)
def hardlock_start(
    body: HardLockStartRequest,
    x_auth: str = Header(default=""),
    x_device_secret: str = Header(default=""),
) -> LockState:
    _check_auth(x_auth)
    if not 1 <= body.days <= MAX_HARDLOCK_DAYS:
        raise HTTPException(400, f"days must be 1..{MAX_HARDLOCK_DAYS}")

    until = (date.fromisoformat(today()) + timedelta(days=body.days)).isoformat()
    with closing(db()) as conn, conn:
        _load_device(conn, body.device_id, x_device_secret)
        conn.execute(
            """
            UPDATE lock_state SET
                hard_lock_until = ?,
                active_break_started_at = NULL,
                active_break_used_ms = 0
            WHERE device_id = ?
            """,
            (until, body.device_id),
        )
        conn.execute(
            "INSERT INTO daily_breaks (device_id, day, breaks_used) VALUES (?, ?, 0) ON CONFLICT(device_id, day) DO UPDATE SET breaks_used = 0",
            (body.device_id, today()),
        )
        row = conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (body.device_id,)).fetchone()
        return _build_state(conn, body.device_id, row)


@router.post("/v1/lock/emergency/disable", response_model=LockState)
def emergency_disable(
    body: DeviceRequest,
    x_auth: str = Header(default=""),
    x_device_secret: str = Header(default=""),
) -> LockState:
    _check_auth(x_auth)
    with closing(db()) as conn, conn:
        row = _load_device(conn, body.device_id, x_device_secret)
        if row["emergency_month"] == current_month():
            raise HTTPException(429, "emergency disable already used this month")
        conn.execute(
            "UPDATE lock_state SET enabled = 0, emergency_month = ? WHERE device_id = ?",
            (current_month(), body.device_id),
        )
        row = conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (body.device_id,)).fetchone()
        return _build_state(conn, body.device_id, row)


@router.post("/v1/lock/emergency/enable", response_model=LockState)
def emergency_enable(
    body: DeviceRequest,
    x_auth: str = Header(default=""),
    x_device_secret: str = Header(default=""),
) -> LockState:
    _check_auth(x_auth)
    with closing(db()) as conn, conn:
        _load_device(conn, body.device_id, x_device_secret)
        conn.execute(
            """
            UPDATE lock_state SET
                enabled = 1,
                hard_lock_until = NULL,
                active_break_started_at = NULL,
                active_break_used_ms = 0
            WHERE device_id = ?
            """,
            (body.device_id,),
        )
        row = conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (body.device_id,)).fetchone()
        return _build_state(conn, body.device_id, row)
