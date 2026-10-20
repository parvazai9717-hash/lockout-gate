"""Reject missing/example server credentials before comparing supplied keys."""
import hmac
import sqlite3
import time
from contextlib import closing

from fastapi import HTTPException


def require_configured_key(configured: str, label: str) -> None:
    if not configured.strip() or configured.strip() in {"change-me", "change-me-too"}:
        raise HTTPException(503, f"{label} not configured")


def require_key(supplied: str, configured: str, label: str) -> None:
    require_configured_key(configured, label)
    if not hmac.compare_digest(supplied.encode(), configured.encode()):
        raise HTTPException(401, f"bad {label}")


ADMIN_MAX_FAILS = 5
ADMIN_THROTTLE_SECONDS = 600


def init_admin_schema(db_path: str) -> None:
    with closing(sqlite3.connect(db_path, timeout=5)) as conn, conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS admin_attempt (
                singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                fails INTEGER NOT NULL DEFAULT 0,
                window_until INTEGER NOT NULL DEFAULT 0,
                blocked_until INTEGER NOT NULL DEFAULT 0
            )
        """)
        # Preserve an existing throttle when upgrading the personal server.
        conn.execute("""
            INSERT OR IGNORE INTO admin_attempt
            SELECT 1, COALESCE(MAX(fails), 0), ?, COALESCE(MAX(blocked_until), 0)
            FROM focus_admin_attempt
        """, (int(time.time()) + ADMIN_THROTTLE_SECONDS,))


def require_admin(supplied: str, configured: str, db_path: str) -> None:
    """One persistent budget for the shared admin key, across routes/devices.

    Commit failures before raising. Rejected attempts during a cooldown never
    extend it. This is a personal-server policy, not multi-user authentication.
    """
    require_configured_key(configured, "admin key")
    with closing(sqlite3.connect(db_path, timeout=5)) as conn, conn:
        conn.execute("BEGIN IMMEDIATE")
        now = int(time.time())
        fails, window_until, blocked_until = conn.execute(
            "SELECT fails, window_until, blocked_until FROM admin_attempt WHERE singleton=1"
        ).fetchone()
        if blocked_until > now:
            rejected, retry_after = 429, blocked_until - now
        elif hmac.compare_digest(supplied.encode(), configured.encode()):
            conn.execute("UPDATE admin_attempt SET fails=0, window_until=0, blocked_until=0 WHERE singleton=1")
            rejected, retry_after = 0, 0
        else:
            if window_until <= now or blocked_until:
                fails, window_until = 0, now + ADMIN_THROTTLE_SECONDS
            fails += 1
            blocked_until = now + ADMIN_THROTTLE_SECONDS if fails >= ADMIN_MAX_FAILS else 0
            conn.execute(
                "UPDATE admin_attempt SET fails=?, window_until=?, blocked_until=? WHERE singleton=1",
                (fails, window_until, blocked_until),
            )
            rejected, retry_after = 401, 0
    if rejected:
        raise HTTPException(
            rejected, "too many attempts" if rejected == 429 else "bad admin key",
            headers={"Retry-After": str(retry_after)} if retry_after else None,
        )
