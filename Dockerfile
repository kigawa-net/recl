FROM gradle:9.6.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon :app:installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home recl
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app/build/install/app/ ./
USER 10001:10001
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/app/bin/app"]
