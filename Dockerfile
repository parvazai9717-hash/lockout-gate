FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY gate.py work.py gemini_verify.py .

# SQLite lives here. Mount a PERSISTENT VOLUME at this path.
# Without one, every redeploy wipes today's counter and hands you a free reset —
# which is exactly the bypass this whole design exists to close.
# proofs/ (screenshots + videos submitted as work evidence) lives on the same
# volume, so it survives redeploys too.
RUN mkdir -p /var/lib/lockout/proofs
ENV LOCKOUT_DB=/var/lib/lockout/gate.db

EXPOSE 8080

# 0.0.0.0, not 127.0.0.1 — the reverse proxy reaches this over the container
# network, so binding to loopback would make it unreachable.
CMD ["uvicorn", "gate:app", "--host", "0.0.0.0", "--port", "8080"]
