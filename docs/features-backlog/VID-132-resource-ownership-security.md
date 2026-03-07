# VID-132: Resource Ownership Security Implementation

## Priority: CRITICAL (Security)

## Problem Statement

Currently, the application lacks resource ownership validation. Any authenticated user can:
- Read, modify, or delete ANY user's RecurringRule
- Access ANY user's CashFlow data
- View rules/cashflows belonging to other users via `/user/{userId}` endpoints

This is a critical security vulnerability that must be fixed before production deployment.

## Current State Analysis

### 1. RecurringRules API - Vulnerable Endpoints

| Endpoint | Method | Vulnerability |
|----------|--------|---------------|
| `/api/v1/recurring-rules/{ruleId}` | GET | Any user can read any rule |
| `/api/v1/recurring-rules/{ruleId}` | PUT | Any user can modify any rule |
| `/api/v1/recurring-rules/{ruleId}` | DELETE | Any user can delete any rule |
| `/api/v1/recurring-rules/{ruleId}/pause` | POST | Any user can pause any rule |
| `/api/v1/recurring-rules/{ruleId}/resume` | POST | Any user can resume any rule |
| `/api/v1/recurring-rules/{ruleId}/regenerate` | POST | Any user can trigger regeneration |
| `/api/v1/recurring-rules/{ruleId}/amount-changes` | POST | Any user can add amount changes |
| `/api/v1/recurring-rules/{ruleId}/amount-changes` | GET | Any user can view amount changes |
| `/api/v1/recurring-rules/{ruleId}/amount-changes/{id}` | DELETE | Any user can remove amount changes |
| `/api/v1/recurring-rules/user/{userId}` | GET | Any user can view other users' rules |
| `/api/v1/recurring-rules/cash-flow/{cashFlowId}` | GET | Any user can view rules for any CashFlow |

### 2. CashFlow API - Vulnerable Endpoints

| Endpoint | Method | Vulnerability |
|----------|--------|---------------|
| `/cash-flow/cf={cashFlowId}` | GET | Any user can read any CashFlow |
| `/cash-flow/cf={cashFlowId}/*` | POST/PUT/DELETE | Any user can modify any CashFlow |

### 3. Bank Data Ingestion API - Vulnerable Endpoints

| Endpoint | Method | Vulnerability |
|----------|--------|---------------|
| `/api/v1/bank-data-ingestion/cf={cashFlowId}/*` | ALL | Any user can import data to any CashFlow |

### 4. userId in Request Body

**Problem**: `CreateRuleRequest.userId` is passed by client, allowing impersonation.

**Files affected**:
- `src/main/java/com/multi/vidulum/recurring_rules/app/dto/CreateRuleRequest.java:23`
- `src/main/java/com/multi/vidulum/cashflow/app/CashFlowDto.java` (CreateCashFlowJson)

---

## Implementation Plan

### Phase 1: Create Security Infrastructure

#### Step 1.1: Create ResourceOwnershipService
**File**: `src/main/java/com/multi/vidulum/security/ResourceOwnershipService.java`

```java
@Service
@RequiredArgsConstructor
public class ResourceOwnershipService {

    private final DomainCashFlowRepository cashFlowRepository;
    private final DomainRecurringRuleRepository ruleRepository;

    /**
     * Verifies that the current user owns the specified CashFlow.
     * @throws AccessDeniedException if user doesn't own the resource
     */
    public void verifyCashFlowOwnership(CashFlowId cashFlowId);

    /**
     * Verifies that the current user owns the specified RecurringRule.
     * @throws AccessDeniedException if user doesn't own the resource
     */
    public void verifyRuleOwnership(RecurringRuleId ruleId);

    /**
     * Gets the current authenticated user's UserId from SecurityContext.
     */
    public UserId getCurrentUserId();

    /**
     * Checks if the current user owns the CashFlow (without throwing).
     */
    public boolean isOwnerOfCashFlow(CashFlowId cashFlowId);

    /**
     * Checks if the current user owns the RecurringRule (without throwing).
     */
    public boolean isOwnerOfRule(RecurringRuleId ruleId);
}
```

#### Step 1.2: Create AccessDeniedException
**File**: `src/main/java/com/multi/vidulum/security/AccessDeniedException.java`

```java
@Getter
public class AccessDeniedException extends RuntimeException {
    private final String resourceType;
    private final String resourceId;
    private final String userId;

    public AccessDeniedException(String resourceType, String resourceId, String userId) {
        super(String.format("User [%s] does not have access to %s [%s]",
            userId, resourceType, resourceId));
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.userId = userId;
    }
}
```

#### Step 1.3: Add ErrorCode for Access Denied
**File**: `src/main/java/com/multi/vidulum/common/error/ErrorCode.java`

Add:
```java
// Security
ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied to this resource"),
```

#### Step 1.4: Add Exception Handler
**File**: `src/main/java/com/multi/vidulum/security/config/ErrorHttpHandler.java`

Add handler:
```java
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
    log.warn("Access denied: user [{}] tried to access {} [{}]",
        ex.getUserId(), ex.getResourceType(), ex.getResourceId());
    ApiError error = ApiError.of(ErrorCode.ACCESS_DENIED, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}
```

---

### Phase 2: Fix RecurringRules API

#### Step 2.1: Update RecurringRulesController
**File**: `src/main/java/com/multi/vidulum/recurring_rules/app/RecurringRulesController.java`

**Changes**:

1. **Inject ResourceOwnershipService**:
```java
private final ResourceOwnershipService ownershipService;
```

2. **Remove userId from CreateRuleRequest** - get from JWT instead:
```java
@PostMapping
public ResponseEntity<Map<String, String>> createRule(
        @Valid @RequestBody CreateRuleRequest request,
        @RequestHeader("Authorization") String authHeader
) throws RecurringRuleException {
    String authToken = extractToken(authHeader);
    UserId currentUserId = ownershipService.getCurrentUserId(); // <-- NEW

    CreateRuleCommand command = new CreateRuleCommand(
            currentUserId.getId(),  // <-- Changed from request.getUserId()
            request.getCashFlowId(),
            // ... rest unchanged
    );
    // ...
}
```

3. **Add ownership verification to each endpoint**:

```java
@GetMapping("/{ruleId}")
public ResponseEntity<RecurringRuleResponse> getRule(@PathVariable String ruleId) {
    ownershipService.verifyRuleOwnership(RecurringRuleId.of(ruleId)); // <-- ADD
    // ... existing code
}

@PutMapping("/{ruleId}")
public ResponseEntity<Void> updateRule(...) {
    ownershipService.verifyRuleOwnership(RecurringRuleId.of(ruleId)); // <-- ADD
    // ... existing code
}

@DeleteMapping("/{ruleId}")
public ResponseEntity<Void> deleteRule(...) {
    ownershipService.verifyRuleOwnership(RecurringRuleId.of(ruleId)); // <-- ADD
    // ... existing code
}

// Same for: pause, resume, regenerate, amount-changes endpoints
```

4. **Remove or restrict `/user/{userId}` endpoint**:

Option A: Remove entirely (recommended):
```java
// DELETE this endpoint - users should use /me instead
// @GetMapping("/user/{userId}")
```

Option B: Restrict to same user only:
```java
@GetMapping("/user/{userId}")
public ResponseEntity<List<RecurringRuleResponse>> getRulesByUser(@PathVariable String userId) {
    UserId currentUser = ownershipService.getCurrentUserId();
    if (!currentUser.getId().equals(userId)) {
        throw new AccessDeniedException("User", userId, currentUser.getId());
    }
    // ... existing code
}
```

5. **Add CashFlow ownership check for `/cash-flow/{cashFlowId}`**:
```java
@GetMapping("/cash-flow/{cashFlowId}")
public ResponseEntity<List<RecurringRuleResponse>> getRulesByCashFlow(@PathVariable String cashFlowId) {
    ownershipService.verifyCashFlowOwnership(CashFlowId.of(cashFlowId)); // <-- ADD
    // ... existing code
}
```

#### Step 2.2: Update CreateRuleRequest
**File**: `src/main/java/com/multi/vidulum/recurring_rules/app/dto/CreateRuleRequest.java`

**Remove userId field** (line 22-23):
```java
// REMOVE:
// @NotBlank(message = "User ID is required")
// private String userId;
```

---

### Phase 3: Fix CashFlow API

#### Step 3.1: Update CashFlowRestController
**File**: `src/main/java/com/multi/vidulum/cashflow/app/CashFlowRestController.java`

**Changes**:

1. **Inject ResourceOwnershipService**
2. **Remove userId from CreateCashFlowJson** - get from JWT
3. **Add ownership verification to all endpoints that take `{cashFlowId}`**

Example:
```java
@GetMapping("/cf={cashFlowId}")
public CashFlowDto.CashFlowSummaryJson getCashFlow(@PathVariable String cashFlowId) {
    ownershipService.verifyCashFlowOwnership(CashFlowId.of(cashFlowId)); // <-- ADD
    // ... existing code
}
```

---

### Phase 4: Fix Bank Data Ingestion API

#### Step 4.1: Update BankDataIngestionRestController
**File**: `src/main/java/com/multi/vidulum/bank_data_ingestion/app/BankDataIngestionRestController.java`

**Changes**:

1. **Inject ResourceOwnershipService**
2. **Add ownership verification** at the start of each endpoint:

```java
@PostMapping("/upload")
public UploadCsvResponse uploadCsv(
        @PathVariable String cashFlowId,
        @RequestParam("file") MultipartFile file) {
    ownershipService.verifyCashFlowOwnership(CashFlowId.of(cashFlowId)); // <-- ADD
    // ... existing code
}
```

---

### Phase 5: Security Configuration Updates

#### Step 5.1: Update CORS Configuration for Production
**File**: `src/main/java/com/multi/vidulum/security/config/SecurityConfiguration.java`

**Changes**:

1. **Extract CORS origins to configuration**:
```yaml
# application-prod.yml
app:
  cors:
    allowed-origins:
      - https://app.vidulum.com
      - https://vidulum.com
```

2. **Update SecurityConfiguration**:
```java
@Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:8080}")
private List<String> allowedOrigins;

@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins); // <-- Use config
    // ...
}
```

#### Step 5.2: Remove localhost from production CORS
**File**: `src/main/resources/application-prod.yml` (create if not exists)

```yaml
app:
  cors:
    allowed-origins:
      - https://app.vidulum.com
      - https://vidulum.com
```

---

### Phase 6: Update DTO Classes

#### Step 6.1: Remove userId from CreateRuleRequest
**File**: `src/main/java/com/multi/vidulum/recurring_rules/app/dto/CreateRuleRequest.java`

Remove `userId` field entirely.

#### Step 6.2: Remove userId from CashFlowDto.CreateCashFlowJson
**File**: `src/main/java/com/multi/vidulum/cashflow/app/CashFlowDto.java`

Remove `userId` field from `CreateCashFlowJson`.

---

### Phase 7: Update Tests

#### Step 7.1: Update RecurringRulesHttpIntegrationTest
**File**: `src/test/java/com/multi/vidulum/recurring_rules/app/RecurringRulesHttpIntegrationTest.java`

Add tests:
- `shouldReturn403WhenAccessingOtherUsersRule`
- `shouldReturn403WhenModifyingOtherUsersRule`
- `shouldReturn403WhenDeletingOtherUsersRule`
- `shouldReturn403WhenAccessingOtherUsersCashFlowRules`

#### Step 7.2: Update RecurringRulesHttpActor
**File**: `src/test/java/com/multi/vidulum/recurring_rules/app/RecurringRulesHttpActor.java`

Remove `userId` from `CreateRuleRequest` construction.

#### Step 7.3: Update other test files
- `CashFlowControllerTest.java`
- `BankDataIngestionHttpIntegrationTest.java`

---

## Files to Modify Summary

### New Files (3)
1. `src/main/java/com/multi/vidulum/security/ResourceOwnershipService.java`
2. `src/main/java/com/multi/vidulum/security/AccessDeniedException.java`
3. `src/main/resources/application-prod.yml`

### Modified Files (12)
1. `src/main/java/com/multi/vidulum/common/error/ErrorCode.java` - add ACCESS_DENIED
2. `src/main/java/com/multi/vidulum/security/config/ErrorHttpHandler.java` - add handler
3. `src/main/java/com/multi/vidulum/recurring_rules/app/RecurringRulesController.java` - add checks
4. `src/main/java/com/multi/vidulum/recurring_rules/app/dto/CreateRuleRequest.java` - remove userId
5. `src/main/java/com/multi/vidulum/cashflow/app/CashFlowRestController.java` - add checks
6. `src/main/java/com/multi/vidulum/cashflow/app/CashFlowDto.java` - remove userId
7. `src/main/java/com/multi/vidulum/bank_data_ingestion/app/BankDataIngestionRestController.java` - add checks
8. `src/main/java/com/multi/vidulum/security/config/SecurityConfiguration.java` - CORS config
9. `src/test/java/com/multi/vidulum/recurring_rules/app/RecurringRulesHttpIntegrationTest.java`
10. `src/test/java/com/multi/vidulum/recurring_rules/app/RecurringRulesHttpActor.java`
11. `src/test/java/com/multi/vidulum/cashflow/CashFlowControllerTest.java`
12. `src/test/java/com/multi/vidulum/bank_data_ingestion/app/BankDataIngestionHttpIntegrationTest.java`

---

## HTTP Status Codes After Implementation

| Scenario | Status Code |
|----------|-------------|
| User accesses own resource | 200 OK |
| User accesses other user's resource | 403 FORBIDDEN |
| Resource does not exist | 404 NOT_FOUND |
| Invalid token | 401 UNAUTHORIZED |

---

## Estimated Effort

| Phase | Tasks | Complexity |
|-------|-------|------------|
| Phase 1 | Security infrastructure | Medium |
| Phase 2 | RecurringRules API | Medium |
| Phase 3 | CashFlow API | Medium |
| Phase 4 | Bank Data Ingestion API | Low |
| Phase 5 | Security configuration | Low |
| Phase 6 | DTO updates | Low |
| Phase 7 | Tests | Medium |

---

## Acceptance Criteria

1. **403 Forbidden** is returned when user tries to access resource owned by another user
2. **userId is automatically extracted from JWT** - not passed in request body
3. **GET /me** endpoint works correctly for authenticated user
4. **GET /user/{userId}** is either removed or restricted to same user
5. **CORS configuration** is externalized and localhost is excluded in production
6. **All existing tests pass** after modifications
7. **New security tests** verify access control

---

## Security Considerations

1. **Audit Logging**: Consider adding audit logs for access denied events
2. **Rate Limiting**: Consider adding rate limiting for failed access attempts
3. **IDOR Prevention**: This implementation prevents Insecure Direct Object Reference attacks
4. **JWT Secret**: Ensure `jwt.secret-key` is different in production (currently hardcoded in application.yml)

---

## Related Issues

- VID-131: Recurring Rules implementation (current)
- VID-XXX: Rate Limiting (future)
- VID-XXX: Audit Logging (future)
