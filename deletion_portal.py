"""Same-origin, tracker-free deletion page; no recovery key in URLs or logs."""
from pathlib import Path
from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

router = APIRouter()
ROOT = Path(__file__).resolve().parent / "portal"
HEADERS = {"Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
           "X-Frame-Options": "DENY", "X-Content-Type-Options": "nosniff",
           "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; "
           "connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'"}


@router.get("/account-deletion/", include_in_schema=False)
def page():
    return FileResponse(ROOT / "index.html", headers=HEADERS)


@router.get("/account-deletion/{asset}", include_in_schema=False)
def asset(asset: str):
    if asset not in {"delete.js", "style.css"}:
        raise HTTPException(404, "not found")
    return FileResponse(ROOT / asset, headers=HEADERS)
