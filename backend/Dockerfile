# --- Build stage ---
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:25-jre
WORKDIR /app
# Run as a non-root user.
RUN useradd -r -u 1001 handoffly
COPY --from=build /app/target/handoffly-backend.jar app.jar
# Storage dir for local file storage (mount a volume in production or use S3).
RUN mkdir -p /app/storage-data && chown -R handoffly:handoffly /app
USER handoffly
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
