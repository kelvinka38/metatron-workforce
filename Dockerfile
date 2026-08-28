FROM eclipse-temurin:22-jre@sha256:a94532aaca0997d728c6f88927f2209d383a185fbbf7398c8dc41bf4ffd21118
ARG METATRON_COMMIT_SHA=unknown
LABEL org.opencontainers.image.title="Metatron Workforce" \
      org.opencontainers.image.revision="${METATRON_COMMIT_SHA}"
WORKDIR /app
RUN useradd --system --create-home --uid 10001 workforce
COPY build/libs/metatron-workforce-0.1.0.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
