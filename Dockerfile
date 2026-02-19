FROM eclipse-temurin:21-jre-alpine
VOLUME /tmp
COPY target/vidulum-0.0.1-SNAPSHOT.jar vidulum.jar
ENTRYPOINT ["java","-jar", "--enable-preview","vidulum.jar"]