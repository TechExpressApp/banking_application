# Container image for Vercel (Container runtime) or any Docker host.

# ---- build ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/banking-application-1.0.0.jar app.jar

# Vercel sends traffic to port 80 unless PORT is set in the project settings.
ENV PORT=80
# Containers lose local files when they stop: require the Supabase/PostgreSQL database (POSTGRES_URL).
ENV BANKING_REQUIRE_EXTERNAL_DB=true
EXPOSE 80

# Smaller JIT tier = faster cold starts, which matter when the host scales to zero.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:TieredStopAtLevel=1", "-jar", "app.jar"]
