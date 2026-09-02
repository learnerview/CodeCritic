FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
ENV MAVEN_OPTS="-Xmx512m"
COPY java-server/pom.xml .
COPY java-server/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

# Install SpotBugs CLI (required for static bug detection). The base JDK image
# already provides javac, which SpotBugsRunner uses (via ToolProvider) to
# compile submitted source before analysis.
RUN curl -fsSL https://repo1.maven.org/maven2/com/github/spotbugs/spotbugs/4.10.4/spotbugs-4.10.4.tgz -o /tmp/spotbugs.tgz && \
    tar xzf /tmp/spotbugs.tgz -C /opt && \
    rm /tmp/spotbugs.tgz && \
    chmod +x /opt/spotbugs-4.10.4/bin/spotbugs && \
    ln -s /opt/spotbugs-4.10.4/bin/spotbugs /usr/local/bin/spotbugs && \
    javac --version && spotbugs -version

EXPOSE 8080
# Render free tier = 512 MB RAM / 0.1 CPU: cap the JVM hard so growth
# can never kill the instance. Serial GC is the lowest-overhead choice
# for a small heap.
ENTRYPOINT ["java", "-Xmx256m", "-Xms64m", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=64m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]