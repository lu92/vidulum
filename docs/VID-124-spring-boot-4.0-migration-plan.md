# VID-124: Spring Boot 4.0.0 Migration Plan

## Overview

This document describes the migration plan from Spring Boot 3.5.2 to Spring Boot 4.0.0 for the Vidulum application.

**Release Date:** Spring Boot 4.0.0 was released on November 20, 2025
**Current Version:** 3.5.2
**Target Version:** 4.0.0

---

## Prerequisites

| Requirement | Current State | Action Required |
|-------------|---------------|-----------------|
| Java 21+ | Java 21 | None |
| Spring Boot 3.5.x | 3.5.2 | None |
| Fix all deprecation warnings | Unknown | Verify before migration |
| All tests passing | Yes | Confirm |

---

## Breaking Changes Impact Analysis

### High Impact (Requires Code Changes)

#### 1. Jackson 3.0 Migration

**What changed:**
- Package renamed from `com.fasterxml.jackson` to `tools.jackson`
- `ObjectMapper` → `JsonMapper`
- Group ID changed from `com.fasterxml.jackson` to `tools.jackson` (except `jackson-annotations`)

**Affected files in Vidulum (7 files):**

| File | Usage | Migration Action |
|------|-------|------------------|
| `AuthenticationResponse.java` | `@JsonProperty` | Keep as-is (annotations package unchanged) |
| `AuthenticationService.java` | `new ObjectMapper()` | Change to `JsonMapper.builder().build()` |
| `CategoryName.java` | Jackson annotations | Keep annotations, update imports if needed |
| `CategoryNode.java` | Jackson annotations | Keep annotations, update imports if needed |
| `Money.java` | Jackson annotations | Keep annotations, update imports if needed |
| `JsonContent.java` | `new ObjectMapper()` | Change to `JsonMapper.builder().build()` |
| `ApiError.java` | Jackson annotations | Keep annotations, update imports if needed |

**Migration options:**
1. **Option A (Recommended for gradual migration):** Use `spring-boot-jackson2` bridge module
2. **Option B (Full migration):** Update all imports and code to Jackson 3 API

#### 2. TestRestTemplate Changes

**What changed:**
- `@SpringBootTest` no longer auto-provides `TestRestTemplate`
- New package: `org.springframework.boot.resttestclient.TestRestTemplate`
- Requires explicit `@AutoConfigureTestRestTemplate` annotation

**Affected files in Vidulum (5 files):**

| File | Action Required |
|------|-----------------|
| `AbstractHttpIntegrationTest.java` | Add `@AutoConfigureTestRestTemplate` |
| `AuthenticationHttpActor.java` | Update import |
| `CashFlowHttpActor.java` | Update import |
| `BankDataIngestionHttpActor.java` | Update import |
| `BankDataIngestionHttpIntegrationTest.java` | Add `@AutoConfigureTestRestTemplate` |

#### 3. jjwt Library Compatibility

**What changed:**
- `jjwt-jackson` (0.12.6) depends on Jackson 2
- Spring Boot 4.0 ships with Jackson 3

**Current dependencies:**
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
</dependency>
```

**Migration options:**
1. Use `spring-boot-jackson2` bridge to keep Jackson 2 alongside Jackson 3
2. Wait for jjwt release with Jackson 3 support
3. Switch to alternative JWT library with Jackson 3 support

### Medium Impact (Configuration Changes)

#### 4. Starter Renames

| Current (3.5.2) | New (4.0.0) | Status |
|-----------------|-------------|--------|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | Required |
| `spring-boot-starter-test` | Remove explicit declaration | Auto-included |
| `spring-boot-starter-data-mongodb` | No change | OK |
| `spring-boot-starter-security` | No change | OK |
| `spring-boot-starter-actuator` | No change | OK |
| `spring-boot-starter-validation` | No change | OK |

#### 5. MongoDB Property Renames

| Old Property | New Property |
|--------------|--------------|
| `spring.data.mongodb.uri` | `spring.mongodb.uri` |
| `spring.data.mongodb.host` | `spring.mongodb.host` |
| `spring.data.mongodb.port` | `spring.mongodb.port` |
| `spring.data.mongodb.database` | `spring.mongodb.database` |
| `management.health.mongo.enabled` | `management.health.mongodb.enabled` |

**Note:** Spring Data-specific properties (`auto-index-creation`, `repositories.type`) remain under `spring.data.mongodb`.

### Low Impact (Minor Changes)

#### 6. Kafka Property Renames

| Old Property | New Property |
|--------------|--------------|
| `spring.kafka.retry.topic.backoff.random` | `spring.kafka.retry.topic.backoff.jitter` |

#### 7. Other Property Changes

| Old Property | New Property |
|--------------|--------------|
| `management.tracing.enabled` | `management.tracing.export.enabled` |
| `spring.dao.exceptiontranslation.enabled` | `spring.persistence.exceptiontranslation.enabled` |

---

## Migration Steps

### Phase 0: Pre-Migration Verification

**Estimated time: 30 minutes**

- [ ] Ensure all tests pass on Spring Boot 3.5.2
- [ ] Run `./mvnw compile` and fix any deprecation warnings
- [ ] Create a new branch: `git checkout -b VID-124-spring-boot-4.0`
- [ ] Verify Java 21 is being used: `java -version`

### Phase 1: Add Compatibility Bridges

**Estimated time: 15 minutes**

Update `pom.xml` to add transitional dependencies:

```xml
<!-- Step 1: Update Spring Boot version -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.0</version>
</parent>

<!-- Step 2: Add classic starters for gradual migration -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-classic</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test-classic</artifactId>
    <scope>test</scope>
</dependency>

<!-- Step 3: Add Jackson 2 bridge for jjwt compatibility -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-jackson2</artifactId>
</dependency>

<!-- Step 4: Rename starter-web to starter-webmvc -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<!-- Remove spring-boot-starter-web -->
```

### Phase 2: Fix Compilation Errors

**Estimated time: 1-2 hours**

#### 2.1 Update Test Infrastructure

Add `@AutoConfigureTestRestTemplate` to test classes:

```java
// AbstractHttpIntegrationTest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate  // ADD THIS
public abstract class AbstractHttpIntegrationTest {
    // ...
}

// BankDataIngestionHttpIntegrationTest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate  // ADD THIS
public class BankDataIngestionHttpIntegrationTest {
    // ...
}
```

#### 2.2 Update TestRestTemplate Import (if needed)

```java
// Old import (may still work with classic starter)
import org.springframework.boot.test.web.client.TestRestTemplate;

// New import (Spring Boot 4.0 native)
import org.springframework.boot.resttestclient.TestRestTemplate;
```

#### 2.3 Update application.properties/yml (if exists)

```yaml
# Old
spring.data.mongodb.uri: ${MONGODB_URI}

# New
spring.mongodb.uri: ${MONGODB_URI}
```

### Phase 3: Run Tests and Fix Issues

**Estimated time: 1-2 hours**

```bash
# Compile first
./mvnw clean compile

# Run all tests
./mvnw test

# If tests fail, analyze and fix one by one
./mvnw test -Dtest=SpecificTestClass
```

**Common issues to expect:**
1. Import errors for relocated classes
2. Missing beans due to changed auto-configuration
3. Property binding errors due to renamed properties

### Phase 4: Verify Application Startup

**Estimated time: 30 minutes**

```bash
# Package the application
./mvnw package -DskipTests

# Build Docker image
docker build -t vidulum-app:latest .

# Start infrastructure
docker-compose -f docker-compose-final.yml down
docker-compose -f docker-compose-final.yml up -d

# Check logs for errors
docker-compose -f docker-compose-final.yml logs -f vidulum-app
```

### Phase 5: Manual Testing

**Estimated time: 1 hour**

- [ ] User registration and authentication
- [ ] CashFlow creation (standard)
- [ ] CashFlow creation with history
- [ ] CSV upload and import
- [ ] Category mapping
- [ ] Historical import attestation
- [ ] Kafka event processing

### Phase 6: Remove Compatibility Bridges (Optional - Future)

**Estimated time: 2-4 hours**

Once everything is stable, remove transitional dependencies:

#### 6.1 Full Jackson 3 Migration

Update all Jackson usage:

```java
// Old (Jackson 2)
import com.fasterxml.jackson.databind.ObjectMapper;
ObjectMapper mapper = new ObjectMapper();

// New (Jackson 3)
import tools.jackson.databind.json.JsonMapper;
JsonMapper mapper = JsonMapper.builder().build();
```

#### 6.2 Remove Classic Starters

```xml
<!-- Remove these after full migration -->
<!-- spring-boot-starter-classic -->
<!-- spring-boot-starter-test-classic -->
<!-- spring-boot-jackson2 -->
```

#### 6.3 Update All Imports

Replace deprecated imports with Spring Boot 4.0 native ones.

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Jackson 3 breaks jjwt | Medium | High | Use `spring-boot-jackson2` bridge |
| TestRestTemplate issues | Low | Medium | Add `@AutoConfigureTestRestTemplate` |
| MongoDB connection fails | Low | High | Update properties proactively |
| Kafka issues | Low | Low | Property changes are minimal |
| Third-party library incompatibility | Medium | Medium | Test thoroughly, check compatibility |

---

## Rollback Plan

If migration fails:

1. Revert to previous branch: `git checkout VID-123`
2. Rebuild Docker image: `./mvnw package -DskipTests && docker build -t vidulum-app:latest .`
3. Restart containers: `docker-compose -f docker-compose-final.yml down && docker-compose -f docker-compose-final.yml up -d`

---

## Dependencies Compatibility Matrix

| Dependency | Current Version | Spring Boot 4.0 Compatible | Notes |
|------------|-----------------|---------------------------|-------|
| Testcontainers | 1.20.4 | Yes | No changes needed |
| jjwt | 0.12.6 | Partial | Requires Jackson 2 bridge |
| Vavr | 0.10.4 | Yes | No changes needed |
| Commons CSV | 1.10.0 | Yes | No changes needed |
| iban4j | 3.2.7-RELEASE | Yes | No changes needed |
| Awaitility | 4.2.2 | Yes | No changes needed |
| Lombok | managed | Yes | No changes needed |

---

## Estimated Total Effort

| Phase | Time Estimate |
|-------|---------------|
| Phase 0: Pre-Migration | 30 min |
| Phase 1: Add Bridges | 15 min |
| Phase 2: Fix Compilation | 1-2 hours |
| Phase 3: Run Tests | 1-2 hours |
| Phase 4: Verify Startup | 30 min |
| Phase 5: Manual Testing | 1 hour |
| **Total (with bridges)** | **4-6 hours** |
| Phase 6: Remove Bridges (future) | 2-4 hours |

---

## References

- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot 4.0 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)
- [Jackson 3.0 Migration](https://github.com/FasterXML/jackson/wiki/Jackson-3.0-Migration)
- [Spring Framework 7.0 What's New](https://github.com/spring-projects/spring-framework/wiki/What%27s-New-in-Spring-Framework-7.x)

---

## Approval

- [ ] Plan reviewed by: _______________
- [ ] Approved for implementation: Yes / No
- [ ] Target implementation date: _______________
