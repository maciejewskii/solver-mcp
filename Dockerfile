FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew
RUN ./gradlew --no-daemon dependencies >/tmp/gradle-dependencies.log

COPY src src
COPY examples examples
COPY spec-schema.json spec-schema.json
RUN ./gradlew --no-daemon clean fatJar

FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="solver-mcp"
LABEL org.opencontainers.image.description="General-purpose Timefold-powered MCP solver server for AI agents"
LABEL org.opencontainers.image.source="https://github.com/maciejewskii/solver-mcp"
LABEL org.opencontainers.image.licenses="Apache-2.0"

WORKDIR /app
RUN useradd --create-home --shell /usr/sbin/nologin solvermcp
COPY --from=build /workspace/build/libs/solver-mcp-0.1.0-all.jar /app/solver-mcp.jar

USER solvermcp
ENTRYPOINT ["java", "-jar", "/app/solver-mcp.jar"]
