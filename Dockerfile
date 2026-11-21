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

USER 10001:10001

ENTRYPOINT ["python", "app.py", "--data-dir", "/data"]
CMD ["--non-interactive"]
