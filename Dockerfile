FROM eclipse-temurin:26-jre
WORKDIR /app
COPY target/subscription-meter-0.1.0.jar /app/app.jar
USER 10001:10001
EXPOSE 8180
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
