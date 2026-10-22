"""Restore a verified backup with the current deletion ledger, service STOPPED.

Usage: python tools/restore_database.py BACKUP.sqlite LIVE_DB --service-stopped
The live database is preserved in a pre-restore backup. The ledger must be from
the current persistent volume, never an older copy from the backup being used.
"""
import argparse
from contextlib import closing
import os
from pathlib import Path
import sqlite3
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import privacy_maintenance


def restore(backup, live, *, stopped=False):
    if not stopped:
        raise ValueError('Stop the service before restoring')
    backup, live = Path(backup).resolve(), Path(live).resolve()
    ledger = Path(str(live) + '.deletions.sqlite')
    if backup == live or not backup.is_file() or not ledger.is_file():
        raise ValueError('A separate backup and current deletion ledger are required')
    if backup.stat().st_mtime < time.time() - 30 * 86400:
        raise ValueError('Expired backup cannot be restored with a 31-day deletion ledger')
    if Path(str(live) + '-wal').exists() or Path(str(live) + '-journal').exists():
        raise ValueError('Database is not safely stopped; WAL/journal present')
    temp = Path(str(live) + '.restore-pending')
    preserved = Path(str(live) + '.before-restore.bak')
    if temp.exists() or preserved.exists():
        raise ValueError('Preserve or resolve existing restore files first')
    try:
        with closing(sqlite3.connect(f'file:{backup.as_posix()}?mode=ro', uri=True)) as source, closing(sqlite3.connect(temp)) as target:
            source.backup(target)
            if target.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
                raise ValueError('Backup integrity check failed')
            tables = {r[0] for r in target.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if not {'public_focus_account', 'focus_early_exit', 'privacy_backup_meta'} <= tables:
                raise ValueError('This backup needs a separate legacy migration review')
            created = target.execute('SELECT created_at FROM privacy_backup_meta WHERE singleton=1').fetchone()
            if not created or not -60 <= time.time() - created[0] <= 30 * 86400:
                raise ValueError('Expired or future-dated backup cannot be restored')
            target.execute('CREATE TABLE IF NOT EXISTS public_focus_deletion (device_id TEXT PRIMARY KEY, requested_at INTEGER NOT NULL)')
            privacy_maintenance.apply_ledger(target, str(ledger), restoring=True)
            target.commit()
        if live.exists():
            with closing(sqlite3.connect(live)) as source, closing(sqlite3.connect(preserved)) as target:
                source.backup(target)
        os.chmod(temp, 0o600)
        os.replace(temp, live)
    finally:
        if temp.exists():
            temp.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('backup')
    parser.add_argument('live_database')
    parser.add_argument('--service-stopped', action='store_true')
    args = parser.parse_args()
    restore(args.backup, args.live_database, stopped=args.service_stopped)
    print('Database restored; deleted public accounts suppressed. Review before starting service.')
