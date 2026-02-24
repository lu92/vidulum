# TODO: Integration Tests with JWT Authentication

## Problem

Current HTTP integration tests **disable security completely** and don't test the full authentication flow. This means tests don't verify:

1. JWT token validation works correctly
2. Endpoints reject requests without tokens (401 Unauthorized)
3. Endpoints reject requests with invalid/expired tokens (401 Unauthorized)
4. Role-based authorization works (403 Forbidden for insufficient permissions)
5. The `JwtAuthenticationFilter` processes requests correctly

### Evidence

**Bug found during Spring Boot 3.5.2 upgrade** in `JwtService.java`:
```java
// BEFORE (BUG) - always returned true for valid token format
return (extractedUsername.equals(extractedUsername)) && !isTokenExpired(token);

// AFTER (FIXED)
return (extractedUsername.equals(username)) && !isTokenExpired(token);
```

This bug would have been caught if tests used JWT authentication.

### Current Test Architecture

| Test Class | Security | JWT Used |
|------------|----------|----------|
| `AuthenticationControllerTest` | DISABLED | Only tests generation, not usage |
| `CashFlowErrorHandlingTest` | DISABLED | No |
| `BankDataIngestionHttpIntegrationTest` | DISABLED | No |
| `HttpCashFlowServiceClientIntegrationTest` | DISABLED | No |

Security is disabled via:
- `AbstractHttpIntegrationTest.TestSecurityConfig` - permits all requests
- `@ConditionalOnProperty(name = "app.security.enabled")` with `app.security.enabled=false`

---

## Solution: Enable JWT Authentication in Integration Tests

### Step 1: Create `AuthenticatedHttpIntegrationTest` Base Class

Create a new abstract base class that:
- **Keeps security ENABLED** (no `app.security.enabled=false`)
- Provides helper method to authenticate and get JWT token
- Stores token for subsequent requests

```java
@SpringBootTest(
    classes = FixedClockConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import({PortfolioAppConfig.class, TradingAppConfig.class})
@ActiveProfiles("test")
public abstract class AuthenticatedHttpIntegrationTest {

    // Shared Testcontainers (same as current)
    protected static final MongoDBContainer mongoDBContainer;
    protected static final KafkaContainer kafka;

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    // JWT token storage
    protected String accessToken;
    protected String refreshToken;
    protected String userId;

    /**
     * Registers a new user and stores JWT tokens for authenticated requests.
     */
    protected void registerAndAuthenticate(String username, String email, String password) {
        RegisterRequest request = new RegisterRequest(username, email, password);
        ResponseEntity<AuthenticationResponse> response = restTemplate.postForEntity(
            "/api/v1/auth/register", request, AuthenticationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        this.accessToken = response.getBody().getAccessToken();
        this.refreshToken = response.getBody().getRefreshToken();
        this.userId = response.getBody().getUserId();
    }

    /**
     * Creates HTTP headers with JWT Bearer token.
     */
    protected HttpHeaders authenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);  // Authorization: Bearer <token>
        return headers;
    }

    /**
     * Creates HTTP headers without authentication (for testing 401 responses).
     */
    protected HttpHeaders unauthenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
```

### Step 2: Update `*HttpActor` Classes

Modify HTTP Actor classes to accept and use JWT tokens:

```java
@Slf4j
public class CashFlowHttpActor {

    private final TestRestTemplate restTemplate;
    private final String baseUrl;
    private String jwtToken;  // NEW: Store JWT token

    public CashFlowHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
    }

    // NEW: Set JWT token for authenticated requests
    public void setJwtToken(String token) {
        this.jwtToken = token;
    }

    // NEW: Create authenticated headers
    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwtToken != null) {
            headers.setBearerAuth(jwtToken);
        }
        return headers;
    }

    // Existing methods remain the same, they already use jsonHeaders()
}
```

### Step 3: Update Test Classes

Migrate tests to use authentication:

```java
@Slf4j
class CashFlowErrorHandlingTest extends AuthenticatedHttpIntegrationTest {

    private CashFlowHttpActor actor;

    @BeforeEach
    void setUp() {
        // Register user and get JWT token
        String username = "testuser_" + UUID.randomUUID().toString().substring(0, 8);
        registerAndAuthenticate(username, username + "@test.com", "password123");

        // Create actor with JWT token
        actor = new CashFlowHttpActor(restTemplate, port);
        actor.setJwtToken(accessToken);
    }

    // Existing tests remain the same - they now use authenticated requests
}
```

### Step 4: Add Security-Specific Tests

Create dedicated tests for authentication/authorization:

```java
@Slf4j
class CashFlowSecurityTest extends AuthenticatedHttpIntegrationTest {

    @Test
    @DisplayName("Should return 401 when accessing protected endpoint without token")
    void shouldReturn401WithoutToken() {
        // given - no authentication
        CashFlowHttpActor unauthenticatedActor = new CashFlowHttpActor(restTemplate, port);
        // Don't set JWT token

        // when
        ResponseEntity<ApiError> response = unauthenticatedActor.getCashFlowExpectingError("CF123");

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should return 401 with invalid token")
    void shouldReturn401WithInvalidToken() {
        // given
        CashFlowHttpActor actor = new CashFlowHttpActor(restTemplate, port);
        actor.setJwtToken("invalid.jwt.token");

        // when
        ResponseEntity<ApiError> response = actor.getCashFlowExpectingError("CF123");

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should return 401 with expired token")
    void shouldReturn401WithExpiredToken() {
        // given - generate expired token (requires test helper or mocked clock)
        // ...
    }

    @Test
    @DisplayName("Should return 403 when user lacks required role")
    void shouldReturn403WithoutRequiredRole() {
        // given - authenticate as MANAGER, try to access ADMIN-only endpoint
        // ...
    }
}
```

### Step 5: Remove Security Disabling Code

After migration, remove:

1. `AbstractHttpIntegrationTest.TestSecurityConfig` inner class
2. `app.security.enabled=false` from test properties
3. `@ConditionalOnProperty` can remain on `SecurityConfiguration` for other use cases

---

## Migration Strategy

### Phase 1: Add New Infrastructure (Non-Breaking)
- [ ] Create `AuthenticatedHttpIntegrationTest` base class
- [ ] Add `setJwtToken()` method to all `*HttpActor` classes
- [ ] Create `CashFlowSecurityTest` with basic 401/403 tests

### Phase 2: Migrate Existing Tests (One by One)
- [ ] Migrate `CashFlowErrorHandlingTest` to extend `AuthenticatedHttpIntegrationTest`
- [ ] Migrate `BankDataIngestionHttpIntegrationTest`
- [ ] Migrate `HttpCashFlowServiceClientIntegrationTest`

### Phase 3: Cleanup
- [ ] Remove `AbstractHttpIntegrationTest` (old, security-disabled version)
- [ ] Remove `TestSecurityConfig` from individual test classes
- [ ] Update CLAUDE.md with new testing guidelines

---

## Benefits

1. **Tests closer to production** - same security filters, same JWT validation
2. **Catch security bugs early** - like the `isTokenValid()` bug found during upgrade
3. **Test authorization** - verify role-based access control works
4. **Confidence in deployments** - security is tested, not assumed

## Risks

1. **Slower tests** - each test needs to register/authenticate first
2. **More setup code** - tests need to handle authentication
3. **Token expiration** - long-running tests might face token expiration (mitigated by test configuration)

## Estimated Effort

- Phase 1: 2-3 hours
- Phase 2: 1-2 hours per test class
- Phase 3: 30 minutes

---

## References

- Spring Security 6.x Migration Guide
- `JwtService.java` - JWT generation and validation
- `SecurityConfiguration.java` - security filter chain configuration
- `JwtAuthenticationFilter.java` - request authentication filter
