FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY mvnw .
COPY .mvn ./.mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true
COPY src ./src
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:25-jre-alpine
# Takes Alpine security fixes released after the base image was built.
RUN apk upgrade --no-cache
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# /tmp is a noexec tmpfs, so Netty cannot load its epoll library from there; use NIO from the start.
ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=75 -Dio.netty.transport.noNative=true"
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
