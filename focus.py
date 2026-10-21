"""Keep Me Out: server clock, per-device secrets, and persistent admin throttling.

Personal-server preview. Public launch needs user-owned recovery/provisioning.
"""
import hashlib
import hmac
import os
import sqlite3
import time
from contextlib import closing
from datetime import datetime
from zoneinfo import ZoneInfo
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field
from server_auth import init_admin_schema, require_admin, require_configured_key, require_key

router=APIRouter()
DB_PATH=os.getenv("LOCKOUT_DB","/var/lib/lockout/gate.db")
DEVICE_KEY=os.getenv("LOCKOUT_DEVICE_KEY","")
ADMIN_KEY=os.getenv("LOCKOUT_ADMIN_KEY","")
MAX_FOCUS_MINUTES=int(os.getenv("LOCKOUT_MAX_FOCUS_MINUTES",str(60*24*60)))
EARLY_EXIT_LIMIT = 2
TZ = ZoneInfo(os.getenv("LOCKOUT_TZ", "Asia/Karachi"))

def db():
    c=sqlite3.connect(DB_PATH,timeout=5); c.row_factory=sqlite3.Row; return c

def init_schema():
    os.makedirs(os.path.dirname(DB_PATH) or ".",exist_ok=True)
    with closing(db()) as c,c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS focus_lock(device_id TEXT PRIMARY KEY,started_at INTEGER NOT NULL,ends_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS focus_device(device_id TEXT PRIMARY KEY,secret_hash TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS focus_admin_attempt(device_id TEXT PRIMARY KEY,fails INTEGER NOT NULL DEFAULT 0,blocked_until INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS focus_early_exit (
            scope TEXT NOT NULL, device_id TEXT NOT NULL, month TEXT NOT NULL,
            used INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope, device_id)
        );
        """)
    init_admin_schema(DB_PATH)

def _same(a,b):
    return hmac.compare_digest(a.encode(),b.encode())

def _device(c,device_id,key,secret):
    require_key(key,DEVICE_KEY,"device key")
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
    early_exits_remaining:int=EARLY_EXIT_LIMIT
    early_exit_resets_at:int=0
    early_exit_timezone:str=str(TZ)

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

def early_exit_allowance(c, scope, device_id, now):
    current = datetime.fromtimestamp(now, TZ)
    month = current.strftime("%Y-%m")
    year, next_month = (current.year + 1, 1) if current.month == 12 else (current.year, current.month + 1)
    reset = int(datetime(year, next_month, 1, tzinfo=TZ).timestamp())
    row = c.execute("SELECT month,used FROM focus_early_exit WHERE scope=? AND device_id=?",
                    (scope, device_id)).fetchone()
    used = row["used"] if row and row["month"] == month else 0
    return month, max(0, EARLY_EXIT_LIMIT - used), reset

def state_for(c, row, now, scope, device_id):
    _, remaining, reset = early_exit_allowance(c, scope, device_id, now)
    return _state(row, now).model_copy(update={"early_exits_remaining": remaining,
        "early_exit_resets_at": reset, "early_exit_timezone": str(TZ)})

def consume_early_exit(c, scope, device_id, row, now):
    # Caller owns BEGIN IMMEDIATE and ends the lock in the same transaction.
    # Retries of an already ended/expired lock never spend another allowance.
    if row is None or row["ends_at"] <= now:
        return
    month, remaining, _ = early_exit_allowance(c, scope, device_id, now)
    if remaining == 0:
        raise HTTPException(409, "monthly early-exit limit reached")
    c.execute("INSERT INTO focus_early_exit(scope,device_id,month,used) VALUES (?,?,?,1) "
              "ON CONFLICT(scope,device_id) DO UPDATE SET month=excluded.month, "
              "used=CASE WHEN month=excluded.month THEN used+1 ELSE 1 END", (scope, device_id, month))

def active_in_transaction(c, device_id):
    row = _row(c, device_id)
    return row is not None and row["ends_at"] > int(time.time())

def focus_active(device_id):
    with closing(db()) as c:
        return active_in_transaction(c, device_id)

@router.get("/v1/focus/state",response_model=FocusState)
def focus_state(device_id:str,x_auth:str=Header(default=""),x_device_secret:str=Header(default="")):
    with closing(db()) as c,c:
        _device(c,device_id,x_auth,x_device_secret)
        return state_for(c,_row(c,device_id),int(time.time()),"personal",device_id)

@router.post("/v1/focus/start",response_model=FocusState)
def focus_start(body:FocusStartRequest,x_auth:str=Header(default=""),x_device_secret:str=Header(default="")):
    if not 1 <= body.minutes <= MAX_FOCUS_MINUTES: raise HTTPException(400,"invalid focus duration")
    # Check configuration without granting any administrative action.
    require_configured_key(ADMIN_KEY,"admin key")
    with closing(db()) as c,c:
        c.execute("BEGIN IMMEDIATE"); _device(c,body.device_id,x_auth,x_device_secret)
        now=int(time.time())
        row=_row(c,body.device_id); active=row is not None and row["ends_at"] > now
        started=row["started_at"] if active else now
        end=max(row["ends_at"],now+body.minutes*60) if active else now+body.minutes*60
        c.execute("INSERT INTO focus_lock VALUES (?,?,?) ON CONFLICT(device_id) DO UPDATE SET started_at=excluded.started_at,ends_at=excluded.ends_at",(body.device_id,started,end))
        # An ordinary break must not survive into a focus session.
        c.execute("UPDATE lock_state SET active_break_started_at=NULL,active_break_used_ms=0 WHERE device_id=?", (body.device_id,))
        return state_for(c,_row(c,body.device_id),now,"personal",body.device_id)

@router.post("/v1/focus/end",response_model=FocusState)
def focus_end(body:FocusEndRequest,x_auth:str=Header(default=""),x_device_secret:str=Header(default=""),x_admin:str=Header(default="")):
    with closing(db()) as c,c:
        c.execute("BEGIN IMMEDIATE"); _device(c,body.device_id,x_auth,x_device_secret)
    require_admin(x_admin, ADMIN_KEY, DB_PATH)
    with closing(db()) as c,c:
        c.execute("BEGIN IMMEDIATE")
        now=int(time.time())
        consume_early_exit(c,"personal",body.device_id,_row(c,body.device_id),now)
        c.execute("UPDATE focus_lock SET ends_at=? WHERE device_id=? AND ends_at>?",(now,body.device_id,now))
        return state_for(c,_row(c,body.device_id),now,"personal",body.device_id)
