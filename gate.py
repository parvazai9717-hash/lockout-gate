"""
Lockout gate — the half of the system that does not live on the phone.

Design rule: the phone may only ever ASK. It cannot grant itself time.

  * Usage is monotonic. A phone reporting a smaller number than we already have
    on file is ignored, so you can't clear the counter by clearing app data.
  * The daily rollover uses THIS server's clock. Changing the phone's date does
    nothing at all.
  * Early unlock needs ADMIN_KEY, which must never be stored on the phone.
    Write it on paper. Give it to someone. That friction is the whole product.

Run locally:   uvicorn gate:app --host 127.0.0.1 --port 8080
"""

import os
import sqlite3
import time
from contextlib import closing
from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

# --------------------------------------------------------------------- config

DB_PATH = os.getenv("LOCKOUT_DB", "/var/lib/lockout/gate.db")
DEVICE_KEY = os.getenv("LOCKOUT_DEVICE_KEY", "change-me")        # compiled into the APK
ADMIN_KEY = os.getenv("LOCKOUT_ADMIN_KEY", "change-me-too")      # NOT on the phone
TZ = ZoneInfo(os.getenv("LOCKOUT_TZ", "Asia/Karachi"))
LIMIT_MS = int(os.getenv("LOCKOUT_LIMIT_MIN", "30")) * 60_000

app = FastAPI(title="Lockout Gate", docs_url=None, redoc_url=None)

# ----------------------------------------------------------------- persistence

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    device_id      TEXT PRIMARY KEY,
    day            TEXT NOT NULL,   -- server-local YYYY-MM-DD; the rollover anchor
    used_ms        INTEGER NOT NULL DEFAULT 0,
    override_until INTEGER NOT NULL DEFAULT 0,  -- unix seconds, admin escape hatch
    seen_at        INTEGER NOT NULL DEFAULT 0
);
"""


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=5)
    conn.row_factory = sqlite3.Row
    return conn


@app.on_event("startup")
def init_db() -> None:
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    with closing(db()) as conn, conn:
        conn.executescript(SCHEMA)


def today() -> str:
    """Local calendar date on the SERVER. This is the anchor the phone can't move."""
    return datetime.now(TZ).strftime("%Y-%m-%d")


# --------------------------------------------------------------------- schemas


class CheckIn(BaseModel):
    device_id: str
    used_ms: int


class Verdict(BaseModel):
    allowed: bool
    reason: str
    used_ms: int
    remaining_ms: int


# ------------------------------------------------------------------- endpoints


@app.post("/v1/check", response_model=Verdict)
def check(body: CheckIn, x_auth: str = Header(default="")) -> Verdict:
    """
    Called by the phone roughly every 45 seconds.

    Returns allowed=False once the day's budget is spent, and keeps returning it
    until the server's own date rolls over or an admin override is in effect.
    """
    if x_auth != DEVICE_KEY:
        raise HTTPException(401, "bad device key")

    day = today()

    with closing(db()) as conn, conn:
        row = conn.execute(
            "SELECT day, used_ms, override_until FROM devices WHERE device_id = ?",
            (body.device_id,),
        ).fetchone()

        if row is None or row["day"] != day:
            # Unknown device, or the server's date rolled over -> fresh budget.
            used, override_until = 0, 0
        else:
            used, override_until = row["used_ms"], row["override_until"]

        # Monotonic. The phone may only ever report MORE time, never less, so
        # reinstalling the app or wiping its data doesn't reset the counter.
        used = max(used, max(0, body.used_ms))

        conn.execute(
            """
            INSERT INTO devices (device_id, day, used_ms, override_until, seen_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                day            = excluded.day,
                used_ms        = excluded.used_ms,
                override_until = excluded.override_until,
                seen_at        = excluded.seen_at
            """,
            (body.device_id, day, used, override_until, int(time.time())),
        )

    remaining = max(0, LIMIT_MS - used)

    if override_until > time.time():
        mins = int((override_until - time.time()) // 60)
        return Verdict(
            allowed=True,
            reason=f"admin override, {mins} min left",
            used_ms=used,
            remaining_ms=remaining,
        )

    if used >= LIMIT_MS:
        return Verdict(
            allowed=False,
            reason=f"budget spent, resets {day} +1d",
            used_ms=used,
            remaining_ms=0,
        )

    return Verdict(
        allowed=True,
        reason=f"{remaining // 60_000} min left",
        used_ms=used,
        remaining_ms=remaining,
    )


@app.post("/v1/override")
def override(
    device_id: str,
    minutes: int = 15,
    x_admin: str = Header(default=""),
) -> dict:
    """
    The only escape hatch. Requires ADMIN_KEY, which is deliberately not on the
    phone — so unlocking early means physically going and getting it.
    """
    if x_admin != ADMIN_KEY:
        raise HTTPException(401, "bad admin key")
    if not 1 <= minutes <= 240:
        raise HTTPException(400, "minutes must be 1..240")

    until = int(time.time()) + minutes * 60
    with closing(db()) as conn, conn:
        changed = conn.execute(
            "UPDATE devices SET override_until = ? WHERE device_id = ?",
            (until, device_id),
        ).rowcount
    if not changed:
        raise HTTPException(404, "unknown device")

    return {"device_id": device_id, "override_until": until, "minutes": minutes}


@app.get("/v1/status")
def status(device_id: str, x_admin: str = Header(default="")) -> dict:
    """Read-only introspection. Admin-gated so the phone can't poll its own state."""
    if x_admin != ADMIN_KEY:
        raise HTTPException(401, "bad admin key")

    with closing(db()) as conn:
        row = conn.execute(
            "SELECT * FROM devices WHERE device_id = ?", (device_id,)
        ).fetchone()
    if row is None:
        raise HTTPException(404, "unknown device")

    return {
        "device_id": row["device_id"],
        "day": row["day"],
        "used_min": row["used_ms"] // 60_000,
        "limit_min": LIMIT_MS // 60_000,
        "override_active": row["override_until"] > time.time(),
        "last_seen": datetime.fromtimestamp(row["seen_at"], TZ).isoformat(),
    }


@app.get("/healthz")
def healthz() -> dict:
    return {"ok": True, "day": today()}
