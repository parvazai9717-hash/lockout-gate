"""Independent public focus accounts; legacy personal accounts are untouched."""
import hashlib
import hmac
import os
import sqlite3
import time
from contextlib import closing

from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, Field

import focus

router = APIRouter(prefix="/v1/public")


def init_schema():
    with closing(focus.db()) as c, c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS public_focus_account (
            device_id TEXT PRIMARY KEY, secret_hash TEXT NOT NULL UNIQUE,
            recovery_hash TEXT NOT NULL, started_at INTEGER NOT NULL DEFAULT 0,
            ends_at INTEGER NOT NULL DEFAULT 0, recovery_fails INTEGER NOT NULL DEFAULT 0,
            blocked_until INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS public_focus_rate (
            client_hash TEXT PRIMARY KEY, window_until INTEGER NOT NULL, attempts INTEGER NOT NULL
        );
        """)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def rate_limit(request):
    # Never trust a caller-supplied forwarding header. Behind a proxy this is a
    # deliberately shared cap; the hosting proxy may add stricter per-IP limits.
    client = request.client.host if request.client else "unknown"
    salt = focus.DEVICE_KEY or os.getenv("LOCKOUT_PUBLIC_RATE_SECRET", "")
    if not salt:
        raise HTTPException(503, "public service is not configured")
    key = hmac.new(salt.encode(), client.encode(), hashlib.sha256).hexdigest()
    now = int(time.time())
    with closing(focus.db()) as c, c:
        c.execute("BEGIN IMMEDIATE")
        c.execute("DELETE FROM public_focus_rate WHERE window_until<=?", (now,))
        row = c.execute("SELECT attempts FROM public_focus_rate WHERE client_hash=?", (key,)).fetchone()
        if row and row[0] >= 20:
            raise HTTPException(429, "setup rate limit reached")
        c.execute("INSERT INTO public_focus_rate VALUES (?,?,1) ON CONFLICT(client_hash) "
                  "DO UPDATE SET attempts=attempts+1", (key, now + 3600))


class Enroll(BaseModel):
    device_id: str = Field(pattern=r"^[a-zA-Z0-9-]{16,100}$")
    device_secret: str = Field(min_length=32, max_length=256)
    recovery_key: str = Field(min_length=32, max_length=256)


class Recover(BaseModel):
    device_id: str = Field(min_length=16, max_length=100)
    recovery_key: str = Field(min_length=1, max_length=256)
    device_secret: str = Field(min_length=32, max_length=256)


def account(c, device_id, auth):
    row = c.execute("SELECT * FROM public_focus_account WHERE device_id=?", (device_id,)).fetchone()
    if not row or not auth or not hmac.compare_digest(row["secret_hash"], digest(auth)):
        raise HTTPException(401, "public account authentication failed")
    return row


def recovery_check(c, row, supplied):
    """Return an error for the caller to raise AFTER committing failed attempts."""
    now = int(time.time())
    if row["blocked_until"] > now:
        return HTTPException(429, "recovery temporarily blocked")
    if not supplied or not hmac.compare_digest(row["recovery_hash"], digest(supplied)):
        fails = row["recovery_fails"] + 1 if row["blocked_until"] == 0 else 1
        c.execute("UPDATE public_focus_account SET recovery_fails=?,blocked_until=? WHERE device_id=?",
                  (fails, now + 600 if fails >= 5 else 0, row["device_id"]))
        return HTTPException(401, "incorrect recovery key")
    c.execute("UPDATE public_focus_account SET recovery_fails=0,blocked_until=0 WHERE device_id=?",
              (row["device_id"],))
    return None


@router.post("/enroll", response_model=focus.FocusState)
def enroll(body: Enroll, request: Request):
    rate_limit(request)
    if body.device_secret == body.recovery_key:
        raise HTTPException(400, "recovery and device credentials must differ")
    with closing(focus.db()) as c, c:
        c.execute("BEGIN IMMEDIATE")
        row = c.execute("SELECT * FROM public_focus_account WHERE device_id=?", (body.device_id,)).fetchone()
        if row:
            account(c, body.device_id, body.device_secret)
            if row["ends_at"] > int(time.time()):
                raise HTTPException(423, "recover the active account instead")
            # Retry after a lost setup response. Re-keying is allowed only idle.
            c.execute("UPDATE public_focus_account SET recovery_hash=? WHERE device_id=?",
                      (digest(body.recovery_key), body.device_id))
        else:
            count = c.execute("SELECT COUNT(*) FROM public_focus_account").fetchone()[0]
            if count >= int(os.getenv("LOCKOUT_PUBLIC_MAX_ACCOUNTS", "10000")):
                raise HTTPException(503, "public account capacity reached")
            try:
                c.execute("INSERT INTO public_focus_account(device_id,secret_hash,recovery_hash) VALUES (?,?,?)",
                          (body.device_id, digest(body.device_secret), digest(body.recovery_key)))
            except sqlite3.IntegrityError:
                raise HTTPException(409, "device credentials already registered")
        row = c.execute("SELECT * FROM public_focus_account WHERE device_id=?", (body.device_id,)).fetchone()
        return focus._state(row, int(time.time()))


@router.post("/focus/state", response_model=focus.FocusState)
def state(body: focus.FocusEndRequest, x_auth: str = Header(default="")):
    with closing(focus.db()) as c:
        return focus._state(account(c, body.device_id, x_auth), int(time.time()))


@router.post("/focus/start", response_model=focus.FocusState)
def start(body: focus.FocusStartRequest, x_auth: str = Header(default="")):
    if not 1 <= body.minutes <= focus.MAX_FOCUS_MINUTES:
        raise HTTPException(400, "invalid focus duration")
    with closing(focus.db()) as c, c:
        c.execute("BEGIN IMMEDIATE")
        row = account(c, body.device_id, x_auth)
        now = int(time.time())
        active = row["ends_at"] > now
        started = row["started_at"] if active else now
        end = max(row["ends_at"], now + body.minutes * 60) if active else now + body.minutes * 60
        c.execute("UPDATE public_focus_account SET started_at=?,ends_at=? WHERE device_id=?",
                  (started, end, body.device_id))
        return focus.FocusState(active=True, started_at=started, ends_at=end, remaining_s=end-now, server_now=now)


@router.post("/focus/end", response_model=focus.FocusState)
def end(body: focus.FocusEndRequest, x_auth: str = Header(default=""), x_admin: str = Header(default="")):
    with closing(focus.db()) as c, c:
        c.execute("BEGIN IMMEDIATE")
        row = account(c, body.device_id, x_auth)
        error = recovery_check(c, row, x_admin)
        if not error:
            c.execute("UPDATE public_focus_account SET ends_at=? WHERE device_id=?", (int(time.time()), body.device_id))
    if error:
        raise error
    return focus._state(None, int(time.time()))


@router.post("/recover", response_model=focus.FocusState)
def recover(body: Recover, request: Request):
    rate_limit(request)
    with closing(focus.db()) as c, c:
        c.execute("BEGIN IMMEDIATE")
        row = c.execute("SELECT * FROM public_focus_account WHERE device_id=?", (body.device_id,)).fetchone()
        if not row:
            raise HTTPException(401, "incorrect recovery key")
        error = recovery_check(c, row, body.recovery_key)
        if not error:
            if body.device_secret == body.recovery_key:
                raise HTTPException(400, "credentials must differ")
            try:
                c.execute("UPDATE public_focus_account SET secret_hash=? WHERE device_id=?",
                          (digest(body.device_secret), body.device_id))
            except sqlite3.IntegrityError:
                raise HTTPException(409, "device credentials already registered")
        result = focus._state(row, int(time.time()))
    if error:
        raise error
    return result  # Recovery never ends/shortens an active lock.


@router.post("/account/delete")
def delete(body: focus.FocusEndRequest, x_auth: str = Header(default="")):
    with closing(focus.db()) as c, c:
        c.execute("BEGIN IMMEDIATE")
        row = account(c, body.device_id, x_auth)
        if row["ends_at"] > int(time.time()):
            raise HTTPException(423, "finish the lock or end it with the recovery key first")
        c.execute("DELETE FROM public_focus_account WHERE device_id=?", (body.device_id,))
    return {"deleted": True}
