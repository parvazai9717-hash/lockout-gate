"""Keep Me Out: server clock, per-device secrets, and persistent admin throttling.

Personal-server preview. Public launch needs user-owned recovery/provisioning.
"""
import hashlib
import hmac
import os
import sqlite3
import time
from contextlib import closing
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field

router=APIRouter()
DB_PATH=os.getenv("LOCKOUT_DB","/var/lib/lockout/gate.db")
DEVICE_KEY=os.getenv("LOCKOUT_DEVICE_KEY","")
ADMIN_KEY=os.getenv("LOCKOUT_ADMIN_KEY","")
MAX_FOCUS_MINUTES=int(os.getenv("LOCKOUT_MAX_FOCUS_MINUTES",str(60*24*60)))
ADMIN_MAX_FAILS=5
ADMIN_THROTTLE_SECONDS=600

def db():
    c=sqlite3.connect(DB_PATH,timeout=5); c.row_factory=sqlite3.Row; return c

def init_schema():
    os.makedirs(os.path.dirname(DB_PATH) or ".",exist_ok=True)
    with closing(db()) as c,c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS focus_lock(device_id TEXT PRIMARY KEY,started_at INTEGER NOT NULL,ends_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS focus_device(device_id TEXT PRIMARY KEY,secret_hash TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS focus_admin_attempt(device_id TEXT PRIMARY KEY,fails INTEGER NOT NULL DEFAULT 0,blocked_until INTEGER NOT NULL DEFAULT 0);
        """)

def _same(a,b):
    return hmac.compare_digest(a.encode(),b.encode())

def _device(c,device_id,key,secret):
    if not DEVICE_KEY or DEVICE_KEY == "change-me": raise HTTPException(503,"focus server not configured")
    if not _same(key,DEVICE_KEY): raise HTTPException(401,"bad device key")
    if not 1 <= len(device_id) <= 100 or not 32 <= len(secret) <= 256: raise HTTPException(400,"device identity required")
    digest=hashlib.sha256(secret.encode()).hexdigest()
    c.execute("INSERT INTO focus_device VALUES (?,?) ON CONFLICT DO NOTHING",(device_id,digest))
    stored=c.execute("SELECT secret_hash FROM focus_device WHERE device_id=?",(device_id,)).fetchone()[0]
    if not _same(digest,stored): raise HTTPException(401,"device secret mismatch")

class FocusState(BaseModel):
    active:bool
    started_at:int
    ends_at:int
    remaining_s:int
    server_now:int

class FocusStartRequest(BaseModel):
    device_id:str=Field(min_length=1,max_length=100)
    minutes:int

class FocusEndRequest(BaseModel):
    device_id:str=Field(min_length=1,max_length=100)

def _row(c,device_id):
    return c.execute("SELECT * FROM focus_lock WHERE device_id=?",(device_id,)).fetchone()

def _state(row,now):
    if row is None or row["ends_at"] <= now: return FocusState(active=False,started_at=0,ends_at=0,remaining_s=0,server_now=now)
    return FocusState(active=True,started_at=row["started_at"],ends_at=row["ends_at"],remaining_s=row["ends_at"]-now,server_now=now)

def focus_active(device_id):
    with closing(db()) as c:
        row=_row(c,device_id)
        return row is not None and row["ends_at"] > int(time.time())

@router.get("/v1/focus/state",response_model=FocusState)
def focus_state(device_id:str,x_auth:str=Header(default=""),x_device_secret:str=Header(default="")):
    with closing(db()) as c,c:
        _device(c,device_id,x_auth,x_device_secret)
        return _state(_row(c,device_id),int(time.time()))

@router.post("/v1/focus/start",response_model=FocusState)
def focus_start(body:FocusStartRequest,x_auth:str=Header(default=""),x_device_secret:str=Header(default="")):
    if not 1 <= body.minutes <= MAX_FOCUS_MINUTES: raise HTTPException(400,"invalid focus duration")
    if not ADMIN_KEY or ADMIN_KEY == "change-me-too": raise HTTPException(503,"admin recovery must be configured before starting")
    now=int(time.time())
    with closing(db()) as c,c:
        c.execute("BEGIN IMMEDIATE"); _device(c,body.device_id,x_auth,x_device_secret)
        row=_row(c,body.device_id); active=row is not None and row["ends_at"] > now
        started=row["started_at"] if active else now
        end=max(row["ends_at"],now+body.minutes*60) if active else now+body.minutes*60
        c.execute("INSERT INTO focus_lock VALUES (?,?,?) ON CONFLICT(device_id) DO UPDATE SET started_at=excluded.started_at,ends_at=excluded.ends_at",(body.device_id,started,end))
        return _state(_row(c,body.device_id),now)

@router.post("/v1/focus/end",response_model=FocusState)
def focus_end(body:FocusEndRequest,x_auth:str=Header(default=""),x_device_secret:str=Header(default=""),x_admin:str=Header(default="")):
    now=int(time.time()); rejected=0
    with closing(db()) as c,c:
        c.execute("BEGIN IMMEDIATE"); _device(c,body.device_id,x_auth,x_device_secret)
        if not ADMIN_KEY or ADMIN_KEY == "change-me-too": raise HTTPException(503,"admin key not configured")
        c.execute("INSERT INTO focus_admin_attempt(device_id) VALUES (?) ON CONFLICT DO NOTHING",(body.device_id,))
        row=c.execute("SELECT * FROM focus_admin_attempt WHERE device_id=?",(body.device_id,)).fetchone()
        if row["blocked_until"] > now: rejected=429
        elif _same(x_admin,ADMIN_KEY):
            c.execute("UPDATE focus_admin_attempt SET fails=0,blocked_until=0 WHERE device_id=?",(body.device_id,))
            c.execute("UPDATE focus_lock SET ends_at=? WHERE device_id=? AND ends_at>?",(now,body.device_id,now))
        else:
            fails=row["fails"]+1 if row["blocked_until"] == 0 else 1
            until=now+ADMIN_THROTTLE_SECONDS if fails >= ADMIN_MAX_FAILS else 0
            c.execute("UPDATE focus_admin_attempt SET fails=?,blocked_until=? WHERE device_id=?",(fails,until,body.device_id))
            rejected=401
        state=_state(_row(c,body.device_id),now)
    # Raising inside the transaction would roll back the failed-attempt counter.
    if rejected: raise HTTPException(rejected,"too many attempts" if rejected == 429 else "bad admin key")
    return state
