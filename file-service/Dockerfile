FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY mvnw .
COPY .mvn ./.mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true
COPY src ./src
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:25-jre-alpine
# ffmpeg provides ffprobe, used by the upload validation subprocess. Without it the container
# would throw IOException at runtime even though local tests (host ffprobe) pass.
RUN apk add --no-cache ffmpeg
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders"
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]