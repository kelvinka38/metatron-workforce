FROM node:22-bullseye-slim AS node-toolchain

FROM eclipse-temurin:22-jdk-jammy

COPY --from=node-toolchain /usr/local/bin/node /usr/local/bin/node
COPY --from=node-toolchain /usr/local/bin/npm /usr/local/bin/npm
COPY --from=node-toolchain /usr/local/bin/npx /usr/local/bin/npx
COPY --from=node-toolchain /usr/local/lib/node_modules /usr/local/lib/node_modules

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        python3 python-is-python3 python3-pip python3-venv python3-pytest \
        git bash ca-certificates \
    && npm install --global pnpm@10 yarn@1.22.22 \
    && npm cache clean --force \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 workforce \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin workforce

WORKDIR /opt/metatron-sandbox
COPY runtime-sandbox/server.py /opt/metatron-sandbox/server.py
COPY runtime-sandbox/entrypoint.sh /opt/metatron-sandbox/entrypoint.sh
RUN chmod 0555 /opt/metatron-sandbox/server.py /opt/metatron-sandbox/entrypoint.sh

EXPOSE 8090
ENTRYPOINT ["/opt/metatron-sandbox/entrypoint.sh"]
