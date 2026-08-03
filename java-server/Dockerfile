FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
ENV MAVEN_OPTS="-Xmx512m"
COPY java-server/pom.xml .
COPY java-server/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
# Render free tier = 512 MB RAM / 0.1 CPU: cap the JVM hard so growth
# can never kill the instance. Serial GC is the lowest-overhead choice
# for a small heap.
ENTRYPOINT ["java", "-Xmx256m", "-Xms64m", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=64m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
