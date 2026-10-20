"""Security regressions; no real credentials or external verification requests."""
import io

import pytest
from fastapi import UploadFile

from test_focus import ADM, DEV, client, start


@pytest.mark.parametrize("configured", ["", " ", "change-me", "change-me-too"])
def test_unconfigured_device_credentials_fail_closed_everywhere(client, monkeypatch, configured):
    import focus
    import gate
    import work

    for module in (focus, gate, work):
        monkeypatch.setattr(module, "DEVICE_KEY", configured)
    headers = {**DEV, "X-Auth": configured.strip()}
    assert client.post("/v1/check", json={"device_id": "primary", "used_ms": 0}, headers=headers).status_code == 503
    assert client.get("/v1/lock/state", params={"device_id": "primary"}, headers=headers).status_code == 503
    assert start(client, 5, headers).status_code == 503
    assert client.get("/healthz").status_code == 200


@pytest.mark.parametrize("configured", ["", " ", "change-me", "change-me-too"])
def test_unconfigured_admin_cannot_read_override_start_or_end(client, monkeypatch, configured):
    import focus
    import gate

    assert start(client, 5).status_code == 200
    for module in (focus, gate):
        monkeypatch.setattr(module, "ADMIN_KEY", configured)
    headers = {**DEV, "X-Admin": configured.strip()}
    assert client.get("/v1/status", params={"device_id": "primary"}, headers=headers).status_code == 503
    assert client.post("/v1/override", params={"device_id": "primary"}, headers=headers).status_code == 503
    assert start(client, 10).status_code == 503
    assert client.post("/v1/focus/end", json={"device_id": "primary"}, headers=headers).status_code == 503
    assert client.get("/v1/focus/state", params={"device_id": "primary"}, headers=DEV).json()["active"]


def test_configured_legacy_budget_and_admin_remain_compatible(client):
    check = client.post("/v1/check", json={"device_id": "primary", "used_ms": 1000}, headers=DEV)
    assert check.status_code == 200 and check.json()["used_ms"] == 1000
    assert client.post("/v1/override", params={"device_id": "primary"}, headers=DEV).status_code == 401
    assert client.post("/v1/override", params={"device_id": "primary"}, headers=ADM).status_code == 200
    assert client.get("/v1/status", params={"device_id": "primary"}, headers=ADM).json()["override_active"]


def test_verification_exception_does_not_disclose_provider_details(monkeypatch):
    import gemini_verify

    def fail():
        raise RuntimeError("private-provider-detail-that-must-not-appear")

    monkeypatch.setattr(gemini_verify, "_get_client", fail)
    result = gemini_verify.verify_meal(b"test", "image/jpeg")
    assert result.accepted is False
    assert "private-provider-detail" not in result.reasoning


def test_proof_reader_is_bounded_before_provider_request(client, monkeypatch):
    import asyncio
    import work
    from fastapi import HTTPException

    assert client.post("/v1/lock/hardlock/start", json={"device_id": "primary", "days": 1}, headers=DEV).status_code == 200
    monkeypatch.setattr(work, "MAX_PROOF_BYTES", 16)

    class OversizedProof(UploadFile):
        async def read(self, size=-1):
            assert size == 17
            return b"x" * size

    proof = OversizedProof(io.BytesIO(), headers={"content-type": "image/jpeg"})

    def unexpected_provider_call(*args):
        pytest.fail("Oversized input must be rejected before the provider runs")

    monkeypatch.setattr(work.gemini_verify, "verify_meal", unexpected_provider_call)
    with pytest.raises(HTTPException) as caught:
        asyncio.run(work.break_claim("primary", proof, None, DEV["X-Auth"], DEV["X-Device-Secret"]))
    assert caught.value.status_code == 413


@pytest.mark.parametrize("accepted", [False, True])
def test_legacy_multipart_contract_with_patched_dependencies(client, monkeypatch, accepted):
    import gemini_verify

    assert client.post("/v1/lock/hardlock/start", json={"device_id": "primary", "days": 1}, headers=DEV).status_code == 200

    def verify(content, content_type):
        assert content == b"synthetic-image-bytes" and content_type == "image/jpeg"
        return gemini_verify.VerifyResult(accepted=accepted, confidence=0.9, reasoning="test verdict")

    monkeypatch.setattr(gemini_verify, "verify_meal", verify)
    response = client.post(
        "/v1/break/claim", headers=DEV, data={"device_id": "primary"},
        files={"file": ("test.jpg", b"synthetic-image-bytes", "image/jpeg")},
    )
    assert response.status_code == 200
    assert response.json()["accepted"] is accepted
    assert response.json()["active_break"] is accepted
    assert response.json()["breaks_used_today"] == (1 if accepted else 0)
