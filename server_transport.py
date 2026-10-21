"""Authenticate device requests before parsing; bound streamed request bodies."""
from fastapi import HTTPException
from starlette.responses import JSONResponse

from server_auth import require_key

JSON_LIMIT = 64 * 1024
UPLOAD_LIMIT = 21 * 1024 * 1024  # 20 MiB photo plus multipart envelope
ADMIN_ROUTES = {"/v1/status", "/v1/override"}
PUBLIC_ROUTES = {
    "/v1/public/enroll", "/v1/public/recover", "/v1/public/focus/state",
    "/v1/public/focus/start", "/v1/public/focus/end", "/v1/public/account/delete",
}


class RequestGuard:
    def __init__(self, app, device_key):
        self.app = app
        self.device_key = device_key

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        path = scope["path"].rstrip("/")
        headers = {}
        try:
            for name, value in scope["headers"]:
                if name in {b"x-auth", b"x-admin", b"x-device-secret", b"content-length", b"content-encoding"}:
                    if name in headers:
                        raise HTTPException(400, "duplicate security header")
                    if len(value) > 1024:
                        raise HTTPException(431, "header too large")
                headers[name] = value
            if path.startswith("/v1/") and path not in ADMIN_ROUTES | PUBLIC_ROUTES:
                require_key(headers.get(b"x-auth", b"").decode("latin-1"), self.device_key(), "device key")
            if headers.get(b"content-encoding", b"identity").lower() != b"identity":
                raise HTTPException(415, "compressed request bodies are not supported")
            limit = UPLOAD_LIMIT if path == "/v1/break/claim" else JSON_LIMIT
            if b"content-length" in headers:
                raw_length = headers[b"content-length"]
                if not raw_length.isdigit():
                    raise HTTPException(400, "invalid content length")
                if int(raw_length) > limit:
                    raise HTTPException(413, "request too large")
        except HTTPException as error:
            return await JSONResponse({"detail": error.detail}, status_code=error.status_code,
                headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})(scope, receive, send)

        received = 0

        async def bounded_receive():
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    raise HTTPException(413, "request too large")
            return message

        async def private_send(message):
            if message["type"] == "http.response.start":
                message = {**message, "headers": [
                    *message.get("headers", []),
                    (b"cache-control", b"no-store"),
                    (b"x-content-type-options", b"nosniff"),
                ]}
            await send(message)

        await self.app(scope, bounded_receive, private_send)
