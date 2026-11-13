# syntax=docker/dockerfile:1.7
# Reproducible multi-stage build: compile inside the image, ship a JRE-only runtime as a non-root user.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY shared shared
COPY auth auth
COPY audience audience
COPY app app
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --console=plain :app:bootJar -x test \
 && java -Djarmode=tools -jar app/build/libs/cep-app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 cep && useradd --system --uid 10001 --gid cep --no-create-home cep
WORKDIR /app
# Layered copy: dependencies change rarely, application classes often.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
