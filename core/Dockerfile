FROM python:3.12-slim

# Toolchains the agent needs to build/test typical repos (Python, Java/Gradle, Node).
RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates openjdk-21-jdk-headless nodejs npm \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /data && chmod 711 /data

WORKDIR /app
COPY metatron_core ./metatron_core
# Core stays root (with the few capabilities compose grants) only to run each task as its own
# Unix user, CORE_AGENT_UID_BASE + task id. That user cannot read Core's environment or /data.
ENV PYTHONUNBUFFERED=1 CORE_DATA_DIR=/data PORT=8095 CORE_AGENT_UID_BASE=20000
EXPOSE 8095
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8095/health',timeout=2)"
CMD ["python", "-m", "metatron_core.app"]
