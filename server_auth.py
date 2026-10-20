"""Reject missing/example server credentials before comparing supplied keys."""
import hmac

from fastapi import HTTPException


def require_configured_key(configured: str, label: str) -> None:
    if not configured.strip() or configured.strip() in {"change-me", "change-me-too"}:
        raise HTTPException(503, f"{label} not configured")


def require_key(supplied: str, configured: str, label: str) -> None:
    require_configured_key(configured, label)
    if not hmac.compare_digest(supplied.encode(), configured.encode()):
        raise HTTPException(401, f"bad {label}")
