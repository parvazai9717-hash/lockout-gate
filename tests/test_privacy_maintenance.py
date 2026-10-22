"""Disposable accounts/databases only. No production access or key usage."""
from contextlib import closing
from pathlib import Path
import shutil
import sqlite3
import os
import time

import pytest
from test_focus import client, DEV, start as personal_start
from test_public_focus import ID, KEY, SECRET, AUTH, enroll, start, state


def key_delete(c, key=KEY):
    return c.post('/v1/public/account/delete-with-key', json={'device_id': ID, 'recovery_key': key})


def snapshot(path):
    import privacy_maintenance as pm
    with closing(sqlite3.connect(pm.database_path())) as source, closing(sqlite3.connect(path)) as target:
        source.backup(target)
        target.execute('CREATE TABLE IF NOT EXISTS privacy_backup_meta (singleton INTEGER PRIMARY KEY, created_at INTEGER NOT NULL)')
        target.execute('INSERT OR REPLACE INTO privacy_backup_meta VALUES (1,?)', (int(time.time()),))
        target.commit()


def test_key_delete_verifies_ownership_and_preserves_other_accounts(client):
    enroll(client)
    personal = personal_start(client, 10).json()
    assert key_delete(client, 'incorrect').status_code == 401
    assert state(client).status_code == 200
    r = key_delete(client)
    assert r.status_code == 200 and r.json()['deleted']
    assert r.headers['cache-control'] == 'no-store'
    assert state(client).status_code == 401
    assert enroll(client).status_code == 409
    assert client.get('/v1/focus/state', params={'device_id': 'primary'}, headers=DEV).json()['ends_at'] == personal['ends_at']


def test_active_key_deletion_queues_without_spending_or_shortening(client, monkeypatch):
    import privacy_maintenance as pm
    enroll(client)
    original = start(client, 1).json()
    receipt = key_delete(client).json()
    assert receipt == {'deleted': False, 'pending': True, 'scheduled_at': original['ends_at']}
    current = state(client).json()
    assert current['ends_at'] == original['ends_at']
    assert current['early_exits_remaining'] == 2
    assert start(client, 60).status_code == 409
    # Key-based recovery cannot cancel a pending request or reset the deadline.
    response = client.post('/v1/public/recover', json={'device_id': ID, 'device_secret': SECRET, 'recovery_key': KEY})
    assert response.json()['ends_at'] == original['ends_at']
    pm.process_deletions(original['ends_at'] - 1)
    assert state(client).status_code == 200
    pm.process_deletions(original['ends_at'])
    assert state(client).status_code == 401


def test_device_authenticated_request_can_be_made_during_lock(client):
    enroll(client)
    original = start(client).json()
    route = '/v1/public/account/request-deletion'
    assert client.post(route, json={'device_id': ID}).status_code == 401
    assert client.post(route, json={'device_id': ID}, headers=DEV).status_code == 401
    for _ in range(2):
        r = client.post(route, json={'device_id': ID}, headers=AUTH)
        assert r.json()['pending'] and r.json()['scheduled_at'] == original['ends_at']
    assert state(client).json()['ends_at'] == original['ends_at']


def test_pending_delete_after_valid_early_exit_is_not_an_extra_exit(client):
    import privacy_maintenance as pm
    enroll(client)
    start(client)
    key_delete(client)
    r = client.post('/v1/public/focus/end', json={'device_id': ID}, headers={**AUTH, 'X-Admin': KEY})
    assert r.json()['early_exits_remaining'] == 1
    pm.process_deletions()
    assert state(client).status_code == 401


def test_wrong_key_attempts_share_persistent_recovery_cooldown(client):
    enroll(client)
    for _ in range(5):
        assert key_delete(client, 'wrong').status_code == 401
    assert key_delete(client).status_code == 429
    assert state(client).status_code == 200


def test_deletion_receipt_contains_hash_only_and_legacy_delete_uses_it(client):
    import privacy_maintenance as pm
    enroll(client)
    assert client.post('/v1/public/account/delete', json={'device_id': ID}, headers=AUTH).json() == {'deleted': True}
    with closing(sqlite3.connect(pm.ledger_path())) as c:
        rows = c.execute('SELECT * FROM deleted_account').fetchall()
    assert len(rows) == 1 and rows[0][0] == pm.identity_hash(ID)
    raw = Path(pm.ledger_path()).read_bytes()
    assert ID.encode() not in raw and KEY.encode() not in raw and SECRET.encode() not in raw


def test_crash_between_receipt_and_live_delete_is_completed(client):
    import privacy_maintenance as pm
    enroll(client)
    with closing(sqlite3.connect(pm.ledger_path())) as c, c:
        c.execute('INSERT INTO deleted_account VALUES (?,?)', (pm.identity_hash(ID), int(time.time())))
    pm.process_deletions()
    assert state(client).status_code == 401


def test_daily_backups_expire_only_recognised_old_files(client):
    import privacy_maintenance as pm
    now = int(time.time())
    backups = Path(pm.database_path() + '.backups')
    expired = backups / f'snapshot-{now // pm.DAY - 31}.sqlite'
    expired.write_bytes(b'old managed copy')
    unrelated = backups / 'keep-this.txt'
    unrelated.write_text('unrelated')
    migration = Path(pm.database_path() + '.before-focus-v1.bak')
    migration.write_bytes(b'old migration')
    os.utime(migration, (now - 31 * pm.DAY, now - 31 * pm.DAY))
    pm.maintain(now)
    assert not expired.exists() and not migration.exists() and unrelated.exists()
    with closing(sqlite3.connect(backups / f'snapshot-{now // pm.DAY}.sqlite')) as c:
        assert c.execute('PRAGMA quick_check').fetchone()[0] == 'ok'
    # A later service restart must not recreate an expired migration snapshot.
    import gate
    gate.init_db()
    assert not migration.exists()


def test_bad_current_backup_never_triggers_pruning(client):
    import privacy_maintenance as pm
    now = int(time.time())
    backups = Path(pm.database_path() + '.backups')
    old = backups / f'snapshot-{now // pm.DAY - 31}.sqlite'
    old.write_bytes(b'old copy')
    current = backups / f'snapshot-{now // pm.DAY}.sqlite'
    current.write_bytes(b'corrupted')
    with pytest.raises(sqlite3.DatabaseError):
        pm.maintain(now)
    assert old.exists()
    pm.safe_maintain()
    assert not pm.status()['maintenance_recently_succeeded']


def test_restore_suppresses_deleted_accounts_and_requires_current_ledger(client, tmp_path):
    import privacy_maintenance as pm
    from tools.restore_database import restore
    enroll(client)
    backup = tmp_path / 'snapshot.sqlite'
    snapshot(backup)
    key_delete(client)
    offline = tmp_path / 'offline.db'
    with pytest.raises(ValueError):
        restore(backup, offline, stopped=True)
    shutil.copy2(pm.ledger_path(), str(offline) + '.deletions.sqlite')
    with pytest.raises(ValueError):
        restore(backup, offline)
    restore(backup, offline, stopped=True)
    with closing(sqlite3.connect(offline)) as c:
        assert c.execute('SELECT COUNT(*) FROM public_focus_account').fetchone()[0] == 0
        assert c.execute('PRAGMA quick_check').fetchone()[0] == 'ok'


def test_expired_backup_cannot_bypass_deletion_ledger(client, tmp_path):
    import privacy_maintenance as pm
    from tools.restore_database import restore
    backup = tmp_path / 'old.sqlite'
    shutil.copy2(pm.database_path(), backup)
    old = time.time() - 31 * pm.DAY
    os.utime(backup, (old, old))
    live = tmp_path / 'offline.db'
    shutil.copy2(pm.ledger_path(), str(live) + '.deletions.sqlite')
    with pytest.raises(ValueError, match='Expired backup'):
        restore(backup, live, stopped=True)


def test_pending_deletion_survives_restore_without_shortening_lock(client, tmp_path):
    import privacy_maintenance as pm
    from tools.restore_database import restore
    enroll(client)
    backup = tmp_path / 'before-request.sqlite'
    snapshot(backup)
    original = start(client, 10).json()
    key_delete(client)
    offline = tmp_path / 'offline.db'
    shutil.copy2(pm.ledger_path(), str(offline) + '.deletions.sqlite')
    restore(backup, offline, stopped=True)
    with closing(sqlite3.connect(offline)) as c:
        assert c.execute('SELECT ends_at FROM public_focus_account').fetchone()[0] == original['ends_at']
        assert c.execute('SELECT COUNT(*) FROM public_focus_deletion').fetchone()[0] == 1


def test_changing_backup_file_date_does_not_evade_expiry(client, tmp_path):
    import privacy_maintenance as pm
    from tools.restore_database import restore
    backup = tmp_path / 'snapshot.sqlite'
    snapshot(backup)
    with closing(sqlite3.connect(backup)) as c, c:
        c.execute('UPDATE privacy_backup_meta SET created_at=?', (int(time.time()) - 31 * pm.DAY,))
    offline = tmp_path / 'offline.db'
    shutil.copy2(pm.ledger_path(), str(offline) + '.deletions.sqlite')
    with pytest.raises(ValueError, match='Expired or future-dated'):
        restore(backup, offline, stopped=True)


def test_deletion_portal_has_no_external_dependencies_or_key_urls(client):
    r = client.get('/account-deletion/')
    assert r.status_code == 200 and 'type="password"' in r.text
    assert r.headers['x-frame-options'] == 'DENY'
    assert r.headers['referrer-policy'] == 'no-referrer'
    assert "form-action 'none'" in r.headers['content-security-policy']
    assert client.get('/account-deletion/delete.js').status_code == 200
    assert client.get('/account-deletion/style.css').status_code == 200
    assert client.get('/account-deletion/gate.py').status_code == 404
    js = client.get('/account-deletion/delete.js').text
    assert 'localStorage' not in js and 'console.' not in js and "method: 'POST'" in js


def test_completed_receipts_expire_after_backups_but_pending_requests_remain(client):
    import privacy_maintenance as pm
    enroll(client)
    key_delete(client)
    other = '87654321-4321-4321-8321-cba987654321'
    other_secret = 'another-test-secret-for-retention-1234567890'
    assert enroll(client, other, other_secret, 'another-recovery-key-for-retention-1234567890').status_code == 200
    body = {'device_id': other}
    headers = {'X-Auth': other_secret}
    assert client.post('/v1/public/focus/start', json={**body, 'minutes': 60 * 24 * 60}, headers=headers).status_code == 200
    assert client.post('/v1/public/account/request-deletion', json=body, headers=headers).json()['pending']
    pm.maintain(int(time.time()) + 32 * pm.DAY)
    with closing(sqlite3.connect(pm.ledger_path())) as c:
        assert c.execute('SELECT COUNT(*) FROM deleted_account').fetchone()[0] == 0
        assert c.execute('SELECT COUNT(*) FROM pending_account').fetchone()[0] == 1
    with closing(sqlite3.connect(pm.database_path())) as c:
        assert c.execute('SELECT COUNT(*) FROM public_focus_account').fetchone()[0] == 1
