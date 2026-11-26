FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/gitwatch.jar /app/gitwatch.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/gitwatch.jar"]
