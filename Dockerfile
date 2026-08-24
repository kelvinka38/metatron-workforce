FROM eclipse-temurin:22-jre
WORKDIR /app
RUN useradd --system --create-home --uid 10001 workforce
COPY build/libs/metatron-workforce-0.1.0.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
