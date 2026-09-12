"""
Work-session accountability layer.

Independent of the daily budget system in gate.py: this tracks a single
active "I'm doing work" session per device, whether it is currently
entertainment-locked, and the proof that unlocks it.

    1. POST /v1/work/start  — declare a task, opens a session (locked).
    2. GET  /v1/work/state  — what the phone polls to know if it's locked.
    3. POST /v1/work/proof  — submit a screenshot/video + note; Gemini
       judges it against the declared task and unlocks on acceptance.
    4. POST /v1/work/end    — close out the session for the day.
"""

import mimetypes
import os
import sqlite3
import time
import uuid
from contextlib import closing

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
MAX_PROOF_BYTES = 20 * 1024 * 1024  # inline Gemini upload cap; no Files API here

SCHEMA = """
CREATE TABLE IF NOT EXISTS work_sessions (
    session_id  TEXT PRIMARY KEY,
    device_id   TEXT NOT NULL,
    task        TEXT NOT NULL,
    started_at  INTEGER NOT NULL,
    unlocked    INTEGER NOT NULL DEFAULT 0,
    ended_at    INTEGER
);

CREATE TABLE IF NOT EXISTS proofs (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id   TEXT NOT NULL,
    device_id    TEXT NOT NULL,
    submitted_at INTEGER NOT NULL,
    media_path   TEXT NOT NULL,
    media_kind   TEXT NOT NULL,
    note         TEXT,
    verdict      TEXT NOT NULL,
    confidence   REAL,
    reasoning    TEXT
);
"""


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=5)
    conn.row_factory = sqlite3.Row
    return conn


def init_schema() -> None:
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    os.makedirs(PROOF_DIR, exist_ok=True)
    with closing(db()) as conn, conn:
        conn.executescript(SCHEMA)


def _check_auth(x_auth: str) -> None:
    if x_auth != DEVICE_KEY:
        raise HTTPException(401, "bad device key")


# --------------------------------------------------------------------- schemas


class WorkStart(BaseModel):
    device_id: str
    task: str


class WorkStarted(BaseModel):
    session_id: str
    task: str
    started_at: int


class WorkState(BaseModel):
    active: bool
    session_id: str | None = None
    task: str | None = None
    unlocked: bool = False


class WorkEnd(BaseModel):
    device_id: str
    session_id: str


class ProofResult(BaseModel):
    accepted: bool
    confidence: float
    reasoning: str


# ------------------------------------------------------------------- endpoints


@router.post("/v1/work/start", response_model=WorkStarted)
def work_start(body: WorkStart, x_auth: str = Header(default="")) -> WorkStarted:
    _check_auth(x_auth)
    now = int(time.time())
    session_id = uuid.uuid4().hex

    with closing(db()) as conn, conn:
        conn.execute(
            "UPDATE work_sessions SET ended_at = ? WHERE device_id = ? AND ended_at IS NULL",
            (now, body.device_id),
        )
        conn.execute(
            """
            INSERT INTO work_sessions (session_id, device_id, task, started_at, unlocked, ended_at)
            VALUES (?, ?, ?, ?, 0, NULL)
            """,
            (session_id, body.device_id, body.task, now),
        )

    return WorkStarted(session_id=session_id, task=body.task, started_at=now)


@router.get("/v1/work/state", response_model=WorkState)
def work_state(device_id: str, x_auth: str = Header(default="")) -> WorkState:
    _check_auth(x_auth)

    with closing(db()) as conn:
        row = conn.execute(
            """
            SELECT session_id, task, unlocked FROM work_sessions
            WHERE device_id = ? AND ended_at IS NULL
            ORDER BY started_at DESC LIMIT 1
            """,
            (device_id,),
        ).fetchone()

    if row is None:
        return WorkState(active=False)

    return WorkState(
        active=True,
        session_id=row["session_id"],
        task=row["task"],
        unlocked=bool(row["unlocked"]),
    )


@router.post("/v1/work/proof", response_model=ProofResult)
async def work_proof(
    device_id: str = Form(...),
    session_id: str = Form(...),
    note: str = Form(""),
    file: UploadFile = File(...),
    x_auth: str = Header(default=""),
) -> ProofResult:
    _check_auth(x_auth)

    with closing(db()) as conn:
        session = conn.execute(
            "SELECT task FROM work_sessions WHERE session_id = ? AND device_id = ? AND ended_at IS NULL",
            (session_id, device_id),
        ).fetchone()
    if session is None:
        raise HTTPException(404, "no active session with that id for this device")

    content_type = file.content_type or "application/octet-stream"
    if content_type.startswith("image/"):
        media_kind = "image"
    elif content_type.startswith("video/"):
        media_kind = "video"
    else:
        raise HTTPException(400, f"unsupported media type: {content_type}")

    media_bytes = await file.read()
    if len(media_bytes) > MAX_PROOF_BYTES:
        raise HTTPException(413, "proof file too large (20MB limit, keep videos short)")

    ext = mimetypes.guess_extension(content_type) or ".bin"
    session_dir = os.path.join(PROOF_DIR, session_id)
    os.makedirs(session_dir, exist_ok=True)
    media_path = os.path.join(session_dir, f"{int(time.time())}_{uuid.uuid4().hex[:8]}{ext}")
    with open(media_path, "wb") as f:
        f.write(media_bytes)

    result = gemini_verify.verify(session["task"], note, media_bytes, content_type)

    now = int(time.time())
    with closing(db()) as conn, conn:
        conn.execute(
            """
            INSERT INTO proofs
                (session_id, device_id, submitted_at, media_path, media_kind, note, verdict, confidence, reasoning)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                session_id,
                device_id,
                now,
                media_path,
                media_kind,
                note,
                "accepted" if result.accepted else "rejected",
                result.confidence,
                result.reasoning,
            ),
        )
        if result.accepted:
            conn.execute(
                "UPDATE work_sessions SET unlocked = 1 WHERE session_id = ?",
                (session_id,),
            )

    return ProofResult(accepted=result.accepted, confidence=result.confidence, reasoning=result.reasoning)


@router.post("/v1/work/end")
def work_end(body: WorkEnd, x_auth: str = Header(default="")) -> dict:
    _check_auth(x_auth)
    now = int(time.time())

    with closing(db()) as conn, conn:
        changed = conn.execute(
            "UPDATE work_sessions SET ended_at = ? WHERE session_id = ? AND device_id = ? AND ended_at IS NULL",
            (now, body.session_id, body.device_id),
        ).rowcount
    if not changed:
        raise HTTPException(404, "no matching active session")

    return {"session_id": body.session_id, "ended_at": now}
