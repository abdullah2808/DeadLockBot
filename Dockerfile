# syntax=docker/dockerfile:1
FROM gradle:8.14-jdk21 AS build
WORKDIR /app
COPY . .
# Optional proxy JVM settings and CA for managed build environments. Neither is
# copied to the runtime image; ordinary CI builds need neither option.
ARG GRADLE_OPTS
RUN --mount=type=secret,id=proxy_ca \
    set -eu; \
    if [ -f /run/secrets/proxy_ca ]; then \
      cp "$JAVA_HOME/lib/security/cacerts" /tmp/build-cacerts; \
      keytool -importcert -noprompt -alias build-proxy -file /run/secrets/proxy_ca \
        -keystore /tmp/build-cacerts -storepass changeit; \
      export GRADLE_OPTS="${GRADLE_OPTS:-} -Djavax.net.ssl.trustStore=/tmp/build-cacerts"; \
    fi; \
    gradle shadowJar --no-daemon; \
    rm -f /tmp/build-cacerts

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core util-linux \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*-all.jar app.jar
COPY scripts/container-entrypoint.sh /usr/local/bin/deadlockbot-entrypoint
ENTRYPOINT ["sh", "/usr/local/bin/deadlockbot-entrypoint"]
CMD ["java", "-Djava.awt.headless=true", "-jar", "app.jar"]
