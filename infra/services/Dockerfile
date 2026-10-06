# syntax=docker/dockerfile:1
# 사용: docker build --build-arg SERVICE=order-api infra/services
FROM gradle:9.8.0-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts ./
COPY order-api/build.gradle.kts order-api/
COPY payment-api/build.gradle.kts payment-api/
ARG SERVICE
COPY ${SERVICE}/src ${SERVICE}/src
# 두 서비스 이미지가 병렬로 빌드될 때 Gradle 캐시 잠금 충돌을 피하려고 캐시를 순차 공유한다.
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked \
    gradle --no-daemon -q :${SERVICE}:bootJar

FROM eclipse-temurin:21-jre
ARG SERVICE
COPY --from=build /src/${SERVICE}/build/libs/app.jar /app/app.jar
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
