# VID-103: Maven Multi-Module Migration

## Cel

Przekształcenie monolitycznego projektu Vidulum w strukturę Maven Multi-Module, umożliwiającą:
- Jeden codebase
- Wiele niezależnych Docker images (mikroserwisy)
- Współdzielony kod (eventy, DTOs, utils)

---

## Obecna struktura

```
vidulum/
├── pom.xml                          # Single module
└── src/main/java/com/multi/vidulum/
    ├── VidulumApplication.java
    ├── bank_data_ingestion/
    ├── cashflow/
    ├── cashflow_forecast_processor/
    ├── common/
    ├── pnl/
    ├── portfolio/
    ├── quotation/
    ├── risk_management/
    ├── security/
    ├── shared/
    ├── task/
    ├── trading/
    └── user/
```

---

## Docelowa struktura

```
vidulum/                                    # ROOT (parent pom)
├── pom.xml                                 # Parent POM - packaging: pom
│
├── vidulum-common/                         # Shared code
│   ├── pom.xml
│   └── src/main/java/com/multi/vidulum/
│       ├── common/                         # Money, Ticker, Currency, etc.
│       ├── shared/                         # CQRS, DDD base classes
│       └── events/                         # All domain events
│
├── vidulum-api/                            # Main REST API
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/multi/vidulum/
│       ├── VidulumApiApplication.java
│       ├── bank_data_ingestion/
│       ├── cashflow/
│       ├── pnl/
│       ├── portfolio/
│       ├── quotation/
│       ├── risk_management/
│       ├── security/
│       ├── task/
│       ├── trading/
│       └── user/
│
├── vidulum-websocket-gateway/              # WebSocket Gateway (NEW)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/multi/vidulum/gateway/
│       ├── WebSocketGatewayApplication.java
│       ├── config/
│       │   ├── WebSocketConfig.java
│       │   └── KafkaConsumerConfig.java
│       ├── handler/
│       │   └── EventWebSocketHandler.java
│       └── service/
│           ├── KafkaEventConsumer.java
│           └── SessionRegistry.java
│
└── vidulum-forecast-processor/             # Kafka processor (OPTIONAL - future)
    ├── pom.xml
    ├── Dockerfile
    └── src/main/java/com/multi/vidulum/processor/
        └── ForecastProcessorApplication.java
```

---

## Kroki migracji

### Krok 1: Utworzenie struktury katalogów

```bash
cd /Users/lucjanbik/IdeaProjects/vidulum

# Utwórz katalogi modułów
mkdir -p vidulum-common/src/main/java/com/multi/vidulum
mkdir -p vidulum-common/src/main/resources
mkdir -p vidulum-common/src/test/java

mkdir -p vidulum-api/src/main/java/com/multi/vidulum
mkdir -p vidulum-api/src/main/resources
mkdir -p vidulum-api/src/test/java

mkdir -p vidulum-websocket-gateway/src/main/java/com/multi/vidulum/gateway
mkdir -p vidulum-websocket-gateway/src/main/resources
mkdir -p vidulum-websocket-gateway/src/test/java
```

### Krok 2: Parent POM (`pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.multi</groupId>
    <artifactId>vidulum-parent</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>Vidulum Parent</name>
    <description>Multi-portfolio financial application - Parent POM</description>

    <modules>
        <module>vidulum-common</module>
        <module>vidulum-api</module>
        <module>vidulum-websocket-gateway</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <spring-boot.version>3.2.4</spring-boot.version>
        <testcontainers.version>1.17.3</testcontainers.version>
        <lombok.version>1.18.30</lombok.version>
        <vavr.version>0.10.4</vavr.version>
        <jjwt.version>0.11.5</jjwt.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Spring Boot BOM -->
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

            <!-- Testcontainers BOM -->
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

            <!-- Internal modules -->
            <dependency>
                <groupId>com.multi</groupId>
                <artifactId>vidulum-common</artifactId>
                <version>${project.version}</version>
            </dependency>

            <!-- Common dependencies -->
            <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </dependency>
            <dependency>
                <groupId>io.vavr</groupId>
                <artifactId>vavr</artifactId>
                <version>${vavr.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-api</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-impl</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-jackson</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <!-- Shared dependencies for ALL modules -->
    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.11.0</version>
                    <configuration>
                        <source>21</source>
                        <target>21</target>
                        <compilerArgs>--enable-preview</compilerArgs>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.1.2</version>
                    <configuration>
                        <argLine>--enable-preview</argLine>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                    <configuration>
                        <excludes>
                            <exclude>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                            </exclude>
                        </excludes>
                        <image>
                            <builder>paketobuildpacks/builder:tiny</builder>
                            <env>
                                <JAVA_TOOL_OPTIONS>--enable-preview</JAVA_TOOL_OPTIONS>
                            </env>
                        </image>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

### Krok 3: Common Module POM (`vidulum-common/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.multi</groupId>
        <artifactId>vidulum-parent</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>vidulum-common</artifactId>
    <packaging>jar</packaging>
    <name>Vidulum Common</name>
    <description>Shared code: events, DTOs, value objects, CQRS infrastructure</description>

    <dependencies>
        <!-- Jackson for JSON serialization -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <!-- Vavr for functional programming -->
        <dependency>
            <groupId>io.vavr</groupId>
            <artifactId>vavr</artifactId>
        </dependency>

        <!-- Spring Context (for @Component, etc.) -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>

        <!-- Validation -->
        <dependency>
            <groupId>jakarta.validation</groupId>
            <artifactId>jakarta.validation-api</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### Krok 4: API Module POM (`vidulum-api/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.multi</groupId>
        <artifactId>vidulum-parent</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>vidulum-api</artifactId>
    <packaging>jar</packaging>
    <name>Vidulum API</name>
    <description>Main REST API - CashFlow, Portfolio, Trading, etc.</description>

    <dependencies>
        <!-- Internal -->
        <dependency>
            <groupId>com.multi</groupId>
            <artifactId>vidulum-common</artifactId>
        </dependency>

        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-mongodb</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <!-- JWT -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mongodb</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>kafka</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### Krok 5: WebSocket Gateway Module POM (`vidulum-websocket-gateway/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.multi</groupId>
        <artifactId>vidulum-parent</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>vidulum-websocket-gateway</artifactId>
    <packaging>jar</packaging>
    <name>Vidulum WebSocket Gateway</name>
    <description>WebSocket gateway for real-time event streaming to clients</description>

    <dependencies>
        <!-- Internal -->
        <dependency>
            <groupId>com.multi</groupId>
            <artifactId>vidulum-common</artifactId>
        </dependency>

        <!-- Spring Boot - WebSocket + WebFlux (reactive) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Kafka Consumer -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <!-- JWT for WebSocket authentication -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>kafka</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### Krok 6: Przeniesienie kodu

```bash
# Przenieś common i shared do vidulum-common
mv src/main/java/com/multi/vidulum/common vidulum-common/src/main/java/com/multi/vidulum/
mv src/main/java/com/multi/vidulum/shared vidulum-common/src/main/java/com/multi/vidulum/

# Przenieś resztę do vidulum-api
mv src/main/java/com/multi/vidulum/* vidulum-api/src/main/java/com/multi/vidulum/
mv src/main/resources/* vidulum-api/src/main/resources/
mv src/test/* vidulum-api/src/test/
```

### Krok 7: Dockerfiles

**`vidulum-api/Dockerfile`:**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/vidulum-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
```

**`vidulum-websocket-gateway/Dockerfile`:**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/vidulum-websocket-gateway-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
```

---

## Komendy Maven

### Build

```bash
# Zbuduj wszystko
./mvnw clean package

# Zbuduj wszystko z pominięciem testów
./mvnw clean package -DskipTests

# Zbuduj tylko jeden moduł (z zależnościami)
./mvnw clean package -pl vidulum-api -am
./mvnw clean package -pl vidulum-websocket-gateway -am
```

### Test

```bash
# Uruchom wszystkie testy
./mvnw test

# Testy tylko dla API
./mvnw test -pl vidulum-api

# Testy tylko dla Gateway
./mvnw test -pl vidulum-websocket-gateway

# Konkretny test
./mvnw test -pl vidulum-api -Dtest=BankDataIngestionControllerTest
```

### Docker Images

```bash
# Spring Boot Build Image (Buildpacks) - RECOMMENDED
./mvnw spring-boot:build-image -pl vidulum-api \
    -Dspring-boot.build-image.imageName=vidulum/api:latest

./mvnw spring-boot:build-image -pl vidulum-websocket-gateway \
    -Dspring-boot.build-image.imageName=vidulum/gateway:latest

# Lub tradycyjny Dockerfile
cd vidulum-api && docker build -t vidulum/api:latest .
cd vidulum-websocket-gateway && docker build -t vidulum/gateway:latest .
```

### Run locally

```bash
# Uruchom API
./mvnw spring-boot:run -pl vidulum-api

# Uruchom Gateway
./mvnw spring-boot:run -pl vidulum-websocket-gateway
```

---

## Docker Compose (docelowy)

```yaml
version: '3.8'

services:
  mongodb:
    image: mongo:4.4.6
    ports:
      - "27017:27017"
    volumes:
      - mongodb_data:/data/db

  kafka:
    image: confluentinc/cp-kafka:7.4.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092

  zookeeper:
    image: confluentinc/cp-zookeeper:7.4.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  vidulum-api:
    image: vidulum/api:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_DATA_MONGODB_URI: mongodb://mongodb:27017/vidulum
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      - mongodb
      - kafka

  vidulum-gateway:
    image: vidulum/gateway:latest
    ports:
      - "8081:8081"
    environment:
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      JWT_SECRET: ${JWT_SECRET}
    depends_on:
      - kafka

volumes:
  mongodb_data:
```

---

## Co idzie do którego modułu?

### vidulum-common (biblioteka)
```
com.multi.vidulum.common/
├── Money.java
├── Currency.java
├── Ticker.java
├── Symbol.java
├── JsonContent.java
├── UserId.java
├── events/
│   ├── CashFlowUnifiedEvent.java
│   └── BankDataIngestionUnifiedEvent.java
└── ...

com.multi.vidulum.shared/
├── cqrs/
│   ├── CommandGateway.java
│   ├── QueryGateway.java
│   └── commands/
├── ddd/
│   ├── Aggregate.java
│   └── event/DomainEvent.java
└── ...
```

### vidulum-api (mikroserwis)
```
com.multi.vidulum/
├── VidulumApiApplication.java
├── bank_data_ingestion/
├── cashflow/
├── cashflow_forecast_processor/
├── pnl/
├── portfolio/
├── quotation/
├── risk_management/
├── security/
├── task/
├── trading/
└── user/
```

### vidulum-websocket-gateway (mikroserwis)
```
com.multi.vidulum.gateway/
├── WebSocketGatewayApplication.java
├── config/
│   ├── WebSocketConfig.java
│   ├── KafkaConsumerConfig.java
│   └── SecurityConfig.java
├── handler/
│   ├── BankDataIngestionEventHandler.java
│   └── CashFlowEventHandler.java
├── service/
│   ├── SessionRegistry.java
│   └── SubscriptionManager.java
└── dto/
    ├── SubscribeRequest.java
    └── EventMessage.java
```

---

## Checklisty migracji

### Faza 1: Struktura
- [ ] Utworzyć katalogi modułów
- [ ] Utworzyć parent pom.xml
- [ ] Utworzyć pom.xml dla każdego modułu
- [ ] Przenieść common/ i shared/ do vidulum-common
- [ ] Przenieść resztę do vidulum-api
- [ ] Naprawić importy
- [ ] Uruchomić `./mvnw clean compile`

### Faza 2: Testy
- [ ] Przenieść testy do odpowiednich modułów
- [ ] Uruchomić `./mvnw test`
- [ ] Naprawić ewentualne błędy

### Faza 3: Docker
- [ ] Utworzyć Dockerfile dla vidulum-api
- [ ] Utworzyć Dockerfile dla vidulum-websocket-gateway
- [ ] Przetestować `./mvnw spring-boot:build-image`
- [ ] Zaktualizować docker-compose

### Faza 4: WebSocket Gateway
- [ ] Zaimplementować WebSocketGatewayApplication
- [ ] Zaimplementować Kafka consumer
- [ ] Zaimplementować WebSocket handler
- [ ] Dodać testy integracyjne

---

## Korzyści

1. **Niezależne deployowanie** - można zaktualizować Gateway bez restartowania API
2. **Niezależne skalowanie** - Gateway: 5 instancji, API: 2 instancje
3. **Mniejsze obrazy Docker** - Gateway ~95MB, API ~180MB
4. **Izolacja błędów** - crash Gateway nie wpływa na API
5. **Szybszy build** - `./mvnw package -pl vidulum-gateway` buduje tylko Gateway
6. **Łatwiejsze testy** - można testować moduły w izolacji
