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
    POST /v1/break/start           — start a normal-mode break (no photo).
    POST /v1/break/claim           — start a hard-lock break (photo + Gemini).
    POST /v1/break/report          — report real usage ms for the active break.
    POST /v1/lock/hardlock/start   — begin an N-day hard lock.
    POST /v1/lock/emergency/disable — turn off all blocking (max 1x/month).
    POST /v1/lock/emergency/enable  — turn blocking back on (unlimited).
"""

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
    active_break_used_ms     INTEGER NOT NULL DEFAULT 0
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


def init_schema() -> None:
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    os.makedirs(PROOF_DIR, exist_ok=True)
    with closing(db()) as conn, conn:
        conn.executescript(SCHEMA)


def _check_auth(x_auth: str) -> None:
    if x_auth != DEVICE_KEY:
        raise HTTPException(401, "bad device key")


def _get_or_create_lock_row(conn: sqlite3.Connection, device_id: str) -> sqlite3.Row:
    conn.execute(
        "INSERT INTO lock_state (device_id) VALUES (?) ON CONFLICT(device_id) DO NOTHING",
        (device_id,),
    )
    return conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (device_id,)).fetchone()


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
    return LockState(
        enabled=enabled,
        locked=enabled and not active_break,
        hard_lock_active=hard_lock_active,
        hard_lock_days_remaining=_hard_lock_days_remaining(row),
        breaks_used_today=breaks_used,
        breaks_remaining_today=max(0, cap - breaks_used),
        active_break=active_break,
        emergency_available=row["emergency_month"] != current_month(),
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


class DeviceRequest(BaseModel):
    device_id: str


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
def lock_state(device_id: str, x_auth: str = Header(default="")) -> LockState:
    _check_auth(x_auth)
    with closing(db()) as conn, conn:
        row = _get_or_create_lock_row(conn, device_id)
        return _build_state(conn, device_id, row)


@router.post("/v1/break/start", response_model=LockState)
def break_start(body: DeviceRequest, x_auth: str = Header(default="")) -> LockState:
    """Start a normal-mode break — no photo needed. Rejected during a hard lock."""
    _check_auth(x_auth)
    with closing(db()) as conn, conn:
        row = _get_or_create_lock_row(conn, body.device_id)
        if not row["enabled"]:
            return _build_state(conn, body.device_id, row)
        if _hard_lock_active(row):
            raise HTTPException(400, "hard lock is active — use /v1/break/claim with a photo instead")
        if row["active_break_started_at"] is not None:
            return _build_state(conn, body.device_id, row)  # already active, idempotent

        breaks_used = _breaks_used_today(conn, body.device_id)
        if breaks_used >= MAX_BREAKS_PER_DAY:
            raise HTTPException(429, f"daily break limit reached ({breaks_used}/{MAX_BREAKS_PER_DAY})")

        now = int(time.time())
        conn.execute(
            "UPDATE lock_state SET active_break_started_at = ?, active_break_used_ms = 0 WHERE device_id = ?",
            (now, body.device_id),
        )
        _increment_breaks_used(conn, body.device_id)
        row = conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (body.device_id,)).fetchone()
        return _build_state(conn, body.device_id, row)


@router.post("/v1/break/claim", response_model=BreakClaimResult)
async def break_claim(
    device_id: str = Form(...),
    file: UploadFile = File(...),
    x_auth: str = Header(default=""),
) -> BreakClaimResult:
    """Start a hard-lock break — requires a Gemini-verified eating photo."""
    _check_auth(x_auth)

    with closing(db()) as conn:
        row = _get_or_create_lock_row(conn, device_id)
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
                "UPDATE lock_state SET active_break_started_at = ?, active_break_used_ms = 0 WHERE device_id = ?",
                (now, device_id),
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
def break_report(body: BreakReportRequest, x_auth: str = Header(default="")) -> LockState:
    """
    Phone reports real foreground-usage ms accumulated since its last report
    while a break is active. Monotonic-safe: a non-positive delta is ignored
    rather than allowed to claw back used time.
    """
    _check_auth(x_auth)
    with closing(db()) as conn, conn:
        row = _get_or_create_lock_row(conn, body.device_id)
        if row["active_break_started_at"] is None:
            return _build_state(conn, body.device_id, row)

        new_used = row["active_break_used_ms"] + max(0, body.delta_ms)
        if new_used >= BREAK_MS:
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
def hardlock_start(body: HardLockStartRequest, x_auth: str = Header(default="")) -> LockState:
    _check_auth(x_auth)
    if not 1 <= body.days <= MAX_HARDLOCK_DAYS:
        raise HTTPException(400, f"days must be 1..{MAX_HARDLOCK_DAYS}")

    until = (date.fromisoformat(today()) + timedelta(days=body.days)).isoformat()
    with closing(db()) as conn, conn:
        _get_or_create_lock_row(conn, body.device_id)
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
def emergency_disable(body: DeviceRequest, x_auth: str = Header(default="")) -> LockState:
    _check_auth(x_auth)
    with closing(db()) as conn, conn:
        row = _get_or_create_lock_row(conn, body.device_id)
        if row["emergency_month"] == current_month():
            raise HTTPException(429, "emergency disable already used this month")
        conn.execute(
            "UPDATE lock_state SET enabled = 0, emergency_month = ? WHERE device_id = ?",
            (current_month(), body.device_id),
        )
        row = conn.execute("SELECT * FROM lock_state WHERE device_id = ?", (body.device_id,)).fetchone()
        return _build_state(conn, body.device_id, row)


@router.post("/v1/lock/emergency/enable", response_model=LockState)
def emergency_enable(body: DeviceRequest, x_auth: str = Header(default="")) -> LockState:
    _check_auth(x_auth)
    with closing(db()) as conn, conn:
        _get_or_create_lock_row(conn, body.device_id)
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
