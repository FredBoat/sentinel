FROM openjdk:11-jdk-slim

ENV ENV docker

WORKDIR /opt/sentinel

COPY sentinel.example.yaml /opt/sentinel/sentinel.yaml
COPY sentinel.jar /opt/sentinel/sentinel.jar

ENTRYPOINT ["java", "-Xmx64m", "-jar", "sentinel.jar"]
