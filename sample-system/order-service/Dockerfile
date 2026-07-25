FROM otel/autoinstrumentation-java@sha256:78886f48b012d0dea0a390d96956d6a98c9aca6de134b245e7965aa68c7a0e2d AS otel
FROM eclipse-temurin@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13
WORKDIR /app
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 sample
COPY target/order-service-*.jar /app/application.jar
COPY --from=otel /javaagent.jar /opt/otel/javaagent.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0555 /app/docker-entrypoint.sh
USER 10001
ENV TZ=UTC
ENTRYPOINT ["/app/docker-entrypoint.sh"]
