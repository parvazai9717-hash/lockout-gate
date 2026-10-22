"""Invalid requests must not echo credentials in diagnostic responses."""
import pytest

from test_focus import client, DEV


@pytest.mark.parametrize("route,payload", [
    ("/v1/public/enroll", {"device_secret": "synthetic-device-secret-1234567890123456",
                           "recovery_key": "synthetic-recovery-secret-12345678901234"}),
    ("/v1/public/recover", {"device_id": "too-short",
                            "device_secret": "synthetic-device-secret-1234567890123456",
                            "recovery_key": "synthetic-recovery-secret-12345678901234"}),
    ("/v1/focus/start", {"device_id": "test", "minutes": "synthetic-recovery-secret-12345678901234"}),
])
def test_invalid_requests_do_not_echo_values(client, route, payload):
    response = client.post(route, json=payload, headers=DEV)
    assert response.status_code == 422
    assert "synthetic-" not in response.text
    assert "input" not in response.text
    assert response.headers["cache-control"] == "no-store"


def test_malformed_json_never_echoes_partial_secret(client):
    response = client.post("/v1/public/enroll", content='{"recovery_key":"synthetic-secret",',
                           headers={"Content-Type": "application/json"})
    assert response.status_code == 422
    assert "synthetic-secret" not in response.text
