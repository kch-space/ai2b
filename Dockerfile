FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TZ=Asia/Shanghai

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 ai2b \
    && useradd --uid 10001 --gid ai2b --no-create-home --home-dir /app --shell /usr/sbin/nologin ai2b \
    && mkdir /data \
    && chown ai2b:ai2b /data

COPY app.py ./app.py
COPY analyst/ ./analyst/
COPY static/ ./static/

USER 10001:10001

EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import json, urllib.request; r = urllib.request.urlopen('http://127.0.0.1:8765/api/health', timeout=3); assert json.load(r)['ok']"]

ENTRYPOINT ["python", "app.py", "--host", "0.0.0.0", "--data-dir", "/data"]
CMD ["--port", "8765"]
