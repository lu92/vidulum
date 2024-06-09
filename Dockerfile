FROM eclipse-temurin:17-jdk-alpine
VOLUME /tmp
COPY target/vidulum-0.0.1-SNAPSHOT.jar vidulum.jar
ENTRYPOINT ["java","-jar", "--enable-preview","vidulum.jar"]