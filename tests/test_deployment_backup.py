"""Upgrade snapshots protect existing sessions before a schema migration."""
import sqlite3
from pathlib import Path

from test_focus import client, start


def test_security_snapshot_is_consistent_preserves_sessions_and_is_not_overwritten(client):
    import gate

    before = start(client, 30).json()
    gate.backup_before_security()
    snapshot = Path(gate.DB_PATH + ".before-security-v2.bak")
    assert snapshot.is_file()
    start(client, 60)
    gate.backup_before_security()
    with sqlite3.connect(snapshot) as conn:
        assert conn.execute("PRAGMA quick_check").fetchone()[0] == "ok"
        assert conn.execute("SELECT ends_at FROM focus_lock WHERE device_id='primary'").fetchone()[0] == before["ends_at"]
    with sqlite3.connect(gate.DB_PATH) as conn:
        assert conn.execute("SELECT ends_at FROM focus_lock WHERE device_id='primary'").fetchone()[0] > before["ends_at"]
