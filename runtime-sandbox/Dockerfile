FROM eclipse-temurin:22-jdk-jammy

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        python3 git bash ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 workforce \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin workforce

WORKDIR /opt/metatron-sandbox
COPY runtime-sandbox/server.py /opt/metatron-sandbox/server.py
COPY runtime-sandbox/entrypoint.sh /opt/metatron-sandbox/entrypoint.sh
RUN chmod 0555 /opt/metatron-sandbox/server.py /opt/metatron-sandbox/entrypoint.sh

EXPOSE 8090
ENTRYPOINT ["/opt/metatron-sandbox/entrypoint.sh"]
