FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY gate.py .

# SQLite lives here. Mount a PERSISTENT VOLUME at this path.
# Without one, every redeploy wipes today's counter and hands you a free reset —
# which is exactly the bypass this whole design exists to close.
RUN mkdir -p /var/lib/lockout
ENV LOCKOUT_DB=/var/lib/lockout/gate.db

EXPOSE 8080

# 0.0.0.0, not 127.0.0.1 — the reverse proxy reaches this over the container
# network, so binding to loopback would make it unreachable.
CMD ["uvicorn", "gate:app", "--host", "0.0.0.0", "--port", "8080"]
