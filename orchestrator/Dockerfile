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
ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=75"
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
