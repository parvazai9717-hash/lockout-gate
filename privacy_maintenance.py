"""Bounded local backups and verified public-account deletion maintenance.

The deletion ledger is deliberately outside database backups. Restore through
tools/restore_database.py while the service is stopped, preserving this ledger.
One Uvicorn worker owns maintenance; SQLite serializes account mutations.
"""
import hashlib
import logging
import os
from pathlib import Path
import sqlite3
import time
from contextlib import closing

from importlib import import_module

DAY = 86400
BACKUP_DAYS = 30
LEDGER_DAYS = 31
_last_success = (None, 0)
LEGACY_SUFFIXES = (".before-focus-v1.bak", ".before-security-v2.bak",
                   ".before-public-focus-v1.bak", ".before-early-exits-v1.bak", ".before-restore.bak")


def database_path():
    return import_module('focus').DB_PATH


def database():
    return import_module('focus').db()


def ledger_path():
    return database_path() + ".deletions.sqlite"


def marker_path():
    return database_path() + ".retention-v1"


def identity_hash(device_id):
    return hashlib.sha256(device_id.encode()).hexdigest()


def init_schema():
    with closing(database()) as c, c:
        c.execute("CREATE TABLE IF NOT EXISTS public_focus_deletion "
                  "(device_id TEXT PRIMARY KEY, requested_at INTEGER NOT NULL)")
    with closing(sqlite3.connect(ledger_path())) as c, c:
        c.execute("CREATE TABLE IF NOT EXISTS deleted_account "
                  "(identity_hash TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)")
        c.execute("CREATE TABLE IF NOT EXISTS pending_account "
                  "(identity_hash TEXT PRIMARY KEY, requested_at INTEGER NOT NULL, due_at INTEGER NOT NULL)")
    os.chmod(ledger_path(), 0o600)


def was_deleted(device_id):
    with closing(sqlite3.connect(ledger_path())) as c:
        return c.execute("SELECT 1 FROM deleted_account WHERE identity_hash=?",
                         (identity_hash(device_id),)).fetchone() is not None


def delete_account(c, device_id, now):
    # Write the independent ledger first. If the process stops before the main
    # transaction commits, startup maintenance completes the deletion safely.
    with closing(sqlite3.connect(ledger_path(), timeout=5)) as ledger, ledger:
        ledger.execute("INSERT INTO deleted_account VALUES (?,?) ON CONFLICT(identity_hash) "
                       "DO UPDATE SET deleted_at=excluded.deleted_at", (identity_hash(device_id), now))
        ledger.execute("PRAGMA secure_delete=ON")
        ledger.execute("DELETE FROM pending_account WHERE identity_hash=?", (identity_hash(device_id),))
    c.execute("PRAGMA secure_delete=ON")
    c.execute("DELETE FROM public_focus_account WHERE device_id=?", (device_id,))
    c.execute("DELETE FROM focus_early_exit WHERE scope='public' AND device_id=?", (device_id,))
    c.execute("DELETE FROM public_focus_deletion WHERE device_id=?", (device_id,))


def pending(c, device_id):
    return c.execute("SELECT 1 FROM public_focus_deletion WHERE device_id=?", (device_id,)).fetchone() is not None


def request_deletion(c, row, now):
    if row["ends_at"] > now:
        with closing(sqlite3.connect(ledger_path(), timeout=5)) as ledger, ledger:
            ledger.execute("INSERT OR IGNORE INTO pending_account VALUES (?,?,?)",
                           (identity_hash(row["device_id"]), now, row["ends_at"]))
        c.execute("INSERT OR IGNORE INTO public_focus_deletion VALUES (?,?)", (row["device_id"], now))
        return {"deleted": False, "pending": True, "scheduled_at": row["ends_at"]}
    delete_account(c, row["device_id"], now)
    return {"deleted": True, "pending": False, "scheduled_at": now}


def apply_ledger(c, ledger_file=None, *, restoring=False):
    """Never resurrect a deleted public account after restoring an old backup."""
    with closing(sqlite3.connect(ledger_file or ledger_path())) as ledger:
        deleted = {r[0] for r in ledger.execute("SELECT identity_hash FROM deleted_account")}
        pending_rows = {r[0]: r[1:] for r in ledger.execute("SELECT identity_hash,requested_at,due_at FROM pending_account")}
    c.execute("PRAGMA secure_delete=ON")
    for (device_id,) in c.execute("SELECT device_id FROM public_focus_account").fetchall():
        if identity_hash(device_id) in deleted:
            c.execute("DELETE FROM public_focus_account WHERE device_id=?", (device_id,))
            c.execute("DELETE FROM focus_early_exit WHERE scope='public' AND device_id=?", (device_id,))
            c.execute("DELETE FROM public_focus_deletion WHERE device_id=?", (device_id,))
        elif identity_hash(device_id) in pending_rows:
            requested, due = pending_rows[identity_hash(device_id)]
            c.execute("INSERT OR IGNORE INTO public_focus_deletion VALUES (?,?)", (device_id, requested))
            if restoring:
                c.execute("UPDATE public_focus_account SET ends_at=MAX(ends_at,?) WHERE device_id=?", (due, device_id))


def process_deletions(now=None):
    now = int(time.time()) if now is None else now
    with closing(database()) as c, c:
        c.execute("BEGIN IMMEDIATE")
        apply_ledger(c)
        c.execute("DELETE FROM public_focus_rate WHERE window_until<=?", (now,))
        rows = c.execute("SELECT a.device_id FROM public_focus_account a JOIN public_focus_deletion d "
                         "ON a.device_id=d.device_id WHERE a.ends_at<=?", (now,)).fetchall()
        for row in rows:
            delete_account(c, row[0], now)


def maintain(now=None):
    """Run every minute; verify a current daily backup before expiring old ones.

    Only specifically named service backup files are eligible. Never follows
    symlinks, touches another service or removes the live database.
    """
    now = int(time.time()) if now is None else now
    process_deletions(now)
    db_file = Path(database_path())
    backup_dir = Path(str(db_file) + ".backups")
    if backup_dir.is_symlink():
        raise RuntimeError("Backup directory must not be a symlink")
    backup_dir.mkdir(mode=0o700, exist_ok=True)
    target = backup_dir / f"snapshot-{now // DAY}.sqlite"
    if target.is_symlink():
        raise RuntimeError("Backup target must not be a symlink")
    if not target.exists():
        temp = backup_dir / "snapshot-pending.tmp"
        if temp.is_symlink():
            raise RuntimeError("Backup temporary file must not be a symlink")
        try:
            with closing(database()) as source, closing(sqlite3.connect(temp)) as dest:
                source.backup(dest)
                dest.execute("CREATE TABLE IF NOT EXISTS privacy_backup_meta "
                             "(singleton INTEGER PRIMARY KEY CHECK(singleton=1), created_at INTEGER NOT NULL)")
                dest.execute("INSERT OR REPLACE INTO privacy_backup_meta VALUES (1,?)", (now,))
                dest.commit()
                if dest.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                    raise RuntimeError("Backup integrity check failed")
            os.chmod(temp, 0o600)
            os.replace(temp, target)
        finally:
            if temp.exists():
                temp.unlink()
    # Check an existing snapshot too; corruption must never trigger pruning.
    with closing(sqlite3.connect(f"file:{target.as_posix()}?mode=ro", uri=True)) as dest:
        if dest.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise RuntimeError("Current backup integrity check failed")
    for path in backup_dir.iterdir():
        name = path.name
        if (name.startswith("snapshot-") and name.endswith(".sqlite")
                and name[9:-7].isdigit() and not path.is_symlink()
                and int(name[9:-7]) * DAY <= now - BACKUP_DAYS * DAY):
            path.unlink()
    for suffix in LEGACY_SUFFIXES:
        path = Path(str(db_file) + suffix)
        if path.is_file() and not path.is_symlink() and path.stat().st_mtime <= now - BACKUP_DAYS * DAY:
            path.unlink()
    # Retain suppression receipts longer than every locally managed backup.
    # Age the ledger only after backup pruning succeeds.
    with closing(sqlite3.connect(ledger_path())) as c, c:
        c.execute("PRAGMA secure_delete=ON")
        c.execute("DELETE FROM deleted_account WHERE deleted_at<=?", (now - LEDGER_DAYS * DAY,))
    Path(marker_path()).touch(exist_ok=True)


def safe_maintain():
    global _last_success
    try:
        maintain()
        _last_success = (database_path(), int(time.time()))
    except Exception:
        _last_success = (None, 0)
        # Do not include account identifiers, paths or submitted values.
        logging.getLogger("uvicorn.error").error("Privacy maintenance failed; operator action required.")


def status():
    path, last_run = _last_success
    return {"backup_retention_days": BACKUP_DAYS, "deletion_receipt_days": LEDGER_DAYS,
            "maintenance_recently_succeeded": path == database_path() and 0 <= time.time() - last_run < 180}

