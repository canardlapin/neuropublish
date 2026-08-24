# syntax=docker/dockerfile:1.7
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.11.7_3.7.4 AS scala-build
WORKDIR /src
COPY . .
RUN sbt -batch frontend/fullLinkJS backend/stageRuntime ingestion/stageRuntime

FROM node:22-bookworm-slim AS web-build
WORKDIR /src/modules/frontend
COPY --from=scala-build /src /src
ENV NP_SCALAJS_OUTPUT_DIR=/src/modules/frontend/target/scala-3.7.4/neuropublish-frontend-opt
RUN npm ci --ignore-scripts && npm run build

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin neuropublish
WORKDIR /app
COPY --from=scala-build /src/modules/backend/target/alpha-runtime /app/backend-runtime
COPY --from=scala-build /src/modules/ingestion/target/alpha-runtime /app/worker-runtime
COPY --from=web-build /src/modules/frontend/dist /app/public
COPY ops/entrypoint.sh /app/entrypoint.sh
RUN chmod 0555 /app/entrypoint.sh && chown -R neuropublish:neuropublish /app
USER neuropublish
ENV NP_HOST=0.0.0.0 NP_PORT=8080 NP_STATIC_DIR=/app/public
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:8080/api/v1/health >/dev/null || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["backend"]
