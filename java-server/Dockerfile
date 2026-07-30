FROM maven:3.9.9-eclipse-temurin-21 AS simplydone4j
WORKDIR /build
COPY SimplyDone4J/pom.xml .
COPY SimplyDone4J/src ./src
RUN mvn -q -DskipTests clean install

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY SimplyDone4J/pom.xml ./simplydone4j/
COPY SimplyDone4J/src ./simplydone4j/src
COPY CodeCritic/java-server/pom.xml .
COPY CodeCritic/java-server/src ./src
COPY --from=simplydone4j /root/.m2 /root/.m2
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
