# accounts-service image (spec §11, §13): `docker compose up -d --build` from a clean clone, with no
# Java installed. Two stages on amazoncorretto:25: the first builds the boot jar with the Gradle
# wrapper (tests run in `./gradlew build`, not here); the second keeps only the jar.

FROM amazoncorretto:25 AS build
# The Gradle wrapper script needs xargs, which the base image lacks. Build stage only.
RUN dnf install -y findutils && dnf clean all
WORKDIR /src
# Wrapper and build scripts first: the Gradle distribution stays in a cached layer while only src/
# changes (the dependencies are resolved by bootJar, after COPY src, so they are fetched again).
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --version --no-daemon
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

FROM amazoncorretto:25
WORKDIR /app
COPY --from=build /src/build/libs/accounts-service.jar app.jar
EXPOSE 8080
# Spec §11: -Xmx2g in the demo (a load at the limits of §6.2 needs ~1 GB just to read it, T08).
ENTRYPOINT ["java", "-Xmx2g", "-jar", "/app/app.jar"]
