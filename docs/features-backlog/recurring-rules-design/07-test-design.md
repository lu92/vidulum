# Test Design - Recurring Rules

**Powiązane:** [06-exceptions-and-errors.md](./06-exceptions-and-errors.md) | [Następny: 08-inconsistencies-and-questions.md](./08-inconsistencies-and-questions.md)

---

## 1. Strategia testowania

### 1.1 Piramida testów

```
                    ┌──────────────┐
                   /   E2E Tests    \          ~5%
                  /   (Selenium/     \
                 /    Playwright)     \
                ├──────────────────────┤
               /   Integration Tests    \      ~20%
              /   (Testcontainers +      \
             /     HTTP + Kafka)          \
            ├──────────────────────────────┤
           /       API Tests (REST)         \   ~25%
          /   (MockMvc + Mock Services)      \
         ├────────────────────────────────────┤
        /           Unit Tests                 \  ~50%
       /   (Domain Logic, Pure Functions)       \
      └──────────────────────────────────────────┘
```

### 1.2 Coverage Targets

| Warstwa | Target | Priorytet |
|---------|--------|-----------|
| Domain Model | 95%+ | Critical |
| Command Handlers | 90%+ | Critical |
| Query Handlers | 80%+ | High |
| REST Controllers | 80%+ | High |
| Infrastructure | 70%+ | Medium |
| Event Handlers | 85%+ | High |

---

## 2. Unit Tests

### 2.1 Domain Model Tests

#### RecurringRule Aggregate Tests

```java
@DisplayName("RecurringRule Aggregate")
class RecurringRuleTest {

    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2026-02-25T10:00:00Z");

    @Nested
    @DisplayName("Create Rule")
    class CreateRule {

        @Test
        @DisplayName("should create active rule with valid parameters")
        void shouldCreateActiveRuleWithValidParameters() {
            // Given
            var ruleId = new RecurringRuleId("RR10000001");
            var cashFlowId = new CashFlowId("CF10000001");
            var userId = UserId.of("U10000001");
            var name = new Name("Wynagrodzenie");
            var description = new Description("Pensja miesięczna");
            var amount = Money.of(new BigDecimal("8500.00"), "PLN");
            var pattern = new MonthlyPattern(10, 1, false);
            var startDate = LocalDate.of(2026, 3, 10);

            // When
            RecurringRule rule = RecurringRule.create(
                    ruleId, cashFlowId, userId, name, description,
                    amount, Type.INFLOW, new CategoryName("Salary"),
                    pattern, startDate, null, FIXED_NOW
            );

            // Then
            assertThat(rule.getSnapshot())
                    .usingRecursiveComparison()
                    .ignoringFields("uncommittedEvents")
                    .isEqualTo(new RecurringRuleSnapshot(
                            ruleId, cashFlowId, userId, name, description,
                            amount, Type.INFLOW, new CategoryName("Salary"),
                            pattern, startDate, null,
                            RuleStatus.ACTIVE,
                            Map.of(), Map.of(), null,
                            0L, FIXED_NOW, FIXED_NOW
                    ));

            assertThat(rule.getUncommittedEvents())
                    .hasSize(1)
                    .first()
                    .isInstanceOf(RecurringRuleEvent.RuleCreatedEvent.class);
        }

        @Test
        @DisplayName("should fail when end date is before start date")
        void shouldFailWhenEndDateBeforeStartDate() {
            // Given
            var startDate = LocalDate.of(2026, 6, 1);
            var endDate = LocalDate.of(2026, 3, 1);  // Before start

            // When & Then
            assertThatThrownBy(() -> RecurringRule.create(
                    new RecurringRuleId("RR10000001"),
                    new CashFlowId("CF10000001"),
                    UserId.of("U10000001"),
                    new Name("Test"),
                    null,
                    Money.of(BigDecimal.TEN, "PLN"),
                    Type.INFLOW,
                    new CategoryName("Cat"),
                    new MonthlyPattern(1, 1, false),
                    startDate,
                    endDate,
                    FIXED_NOW
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("End date must be after start date");
        }

        @Test
        @DisplayName("should fail when required parameter is null")
        void shouldFailWhenRequiredParameterIsNull() {
            assertThatThrownBy(() -> RecurringRule.create(
                    null,  // null ruleId
                    new CashFlowId("CF10000001"),
                    UserId.of("U10000001"),
                    new Name("Test"),
                    null,
                    Money.of(BigDecimal.TEN, "PLN"),
                    Type.INFLOW,
                    new CategoryName("Cat"),
                    new MonthlyPattern(1, 1, false),
                    LocalDate.now(),
                    null,
                    FIXED_NOW
            ))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Rule ID cannot be null");
        }
    }

    @Nested
    @DisplayName("Get Effective Amount")
    class GetEffectiveAmount {

        @Test
        @DisplayName("should return base amount when no changes")
        void shouldReturnBaseAmountWhenNoChanges() {
            // Given
            RecurringRule rule = createBasicRule(Money.of(new BigDecimal("1000.00"), "PLN"));

            // When
            Money effective = rule.getEffectiveAmount(LocalDate.of(2026, 5, 10));

            // Then
            assertThat(effective).isEqualTo(Money.of(new BigDecimal("1000.00"), "PLN"));
        }

        @Test
        @DisplayName("should apply ONE_TIME change only for exact date")
        void shouldApplyOneTimeChangeOnlyForExactDate() {
            // Given
            RecurringRule rule = createBasicRule(Money.of(new BigDecimal("1000.00"), "PLN"));
            rule.addAmountChange(
                    new AmountChangeId("AC10000001"),
                    LocalDate.of(2026, 6, 10),
                    AmountChangeType.ONE_TIME,
                    Money.of(new BigDecimal("2000.00"), "PLN"),
                    "Bonus",
                    FIXED_NOW
            );

            // When & Then
            assertThat(rule.getEffectiveAmount(LocalDate.of(2026, 5, 10)))
                    .isEqualTo(Money.of(new BigDecimal("1000.00"), "PLN"));  // Before

            assertThat(rule.getEffectiveAmount(LocalDate.of(2026, 6, 10)))
                    .isEqualTo(Money.of(new BigDecimal("2000.00"), "PLN"));  // Exact date

            assertThat(rule.getEffectiveAmount(LocalDate.of(2026, 7, 10)))
                    .isEqualTo(Money.of(new BigDecimal("1000.00"), "PLN"));  // After
        }

        @Test
        @DisplayName("should apply PERMANENT change from date onwards")
        void shouldApplyPermanentChangeFromDateOnwards() {
            // Given
            RecurringRule rule = createBasicRule(Money.of(new BigDecimal("1000.00"), "PLN"));
            rule.addAmountChange(
                    new AmountChangeId("AC10000001"),
                    LocalDate.of(2026, 6, 10),
                    AmountChangeType.PERMANENT,
                    Money.of(new BigDecimal("1500.00"), "PLN"),
                    "Raise",
                    FIXED_NOW
            );

            // When & Then
            assertThat(rule.getEffectiveAmount(LocalDate.of(2026, 5, 10)))
                    .isEqualTo(Money.of(new BigDecimal("1000.00"), "PLN"));  // Before

            assertThat(rule.getEffectiveAmount(LocalDate.of(2026, 6, 10)))
                    .isEqualTo(Money.of(new BigDecimal("1500.00"), "PLN"));  // From date

            assertThat(rule.getEffectiveAmount(LocalDate.of(2026, 12, 10)))
                    .isEqualTo(Money.of(new BigDecimal("1500.00"), "PLN"));  // Long after
        }

        @Test
        @DisplayName("should combine ONE_TIME and PERMANENT changes correctly")
        void shouldCombineOneTimeAndPermanentChangesCorrectly() {
            // Given: Base 1000, PERMANENT 1500 from July, ONE_TIME 3000 in June
            RecurringRule rule = createBasicRule(Money.of(new BigDecimal("1000.00"), "PLN"));

            rule.addAmountChange(
                    new AmountChangeId("AC10000001"),
                    LocalDate.of(2026, 6, 10),
                    AmountChangeType.ONE_TIME,
                    Money.of(new BigDecimal("3000.00"), "PLN"),
                    "Bonus",
                    FIXED_NOW
            );

            rule.addAmountChange(
                    new AmountChangeId("AC10000002"),
                    LocalDate.of(2026, 7, 10),
                    AmountChangeType.PERMANENT,
                    Money.of(new BigDecimal("1500.00"), "PLN"),
                    "Raise",
                    FIXED_NOW
            );

            // When & Then
            assertThat(rule.getEffectiveAmount(LocalDate.of(2026, 5, 10)))
                    .isEqualTo(Money.of(new BigDecimal("1000.00"), "PLN"));

            assertThat(rule.getEffectiveAmount(LocalDate.of(2026, 6, 10)))
                    .isEqualTo(Money.of(new BigDecimal("3000.00"), "PLN"));  // ONE_TIME

            assertThat(rule.getEffectiveAmount(LocalDate.of(2026, 7, 10)))
                    .isEqualTo(Money.of(new BigDecimal("1500.00"), "PLN"));  // PERMANENT

            assertThat(rule.getEffectiveAmount(LocalDate.of(2026, 8, 10)))
                    .isEqualTo(Money.of(new BigDecimal("1500.00"), "PLN"));  // PERMANENT continues
        }
    }

    @Nested
    @DisplayName("Lifecycle Operations")
    class LifecycleOperations {

        @Test
        @DisplayName("should pause active rule")
        void shouldPauseActiveRule() {
            // Given
            RecurringRule rule = createBasicRule();

            // When
            rule.pause("Vacation", LocalDate.of(2026, 4, 1), FIXED_NOW);

            // Then
            assertThat(rule.getStatus()).isEqualTo(RuleStatus.PAUSED);
            assertThat(rule.getPauseInfo())
                    .isNotNull()
                    .extracting(PauseInfo::reason, PauseInfo::scheduledResumeDate)
                    .containsExactly("Vacation", LocalDate.of(2026, 4, 1));
        }

        @Test
        @DisplayName("should fail to pause non-active rule")
        void shouldFailToPauseNonActiveRule() {
            // Given
            RecurringRule rule = createBasicRule();
            rule.pause("Test", null, FIXED_NOW);

            // When & Then
            assertThatThrownBy(() -> rule.pause("Again", null, FIXED_NOW))
                    .isInstanceOf(InvalidRuleStatusException.class)
                    .hasMessageContaining("PAUSED");
        }

        @Test
        @DisplayName("should resume paused rule")
        void shouldResumePausedRule() {
            // Given
            RecurringRule rule = createBasicRule();
            rule.pause("Vacation", null, FIXED_NOW);

            // When
            rule.resume(false, FIXED_NOW.plusDays(7));

            // Then
            assertThat(rule.getStatus()).isEqualTo(RuleStatus.ACTIVE);
            assertThat(rule.getPauseInfo()).isNull();
        }

        @Test
        @DisplayName("should delete rule (soft delete)")
        void shouldDeleteRule() {
            // Given
            RecurringRule rule = createBasicRule();

            // When
            rule.delete(false, FIXED_NOW);

            // Then
            assertThat(rule.getStatus()).isEqualTo(RuleStatus.DELETED);
        }

        @Test
        @DisplayName("should fail to modify deleted rule")
        void shouldFailToModifyDeletedRule() {
            // Given
            RecurringRule rule = createBasicRule();
            rule.delete(false, FIXED_NOW);

            // When & Then
            assertThatThrownBy(() -> rule.update(
                    new Name("New Name"), null, null, null, null, null, true, FIXED_NOW
            ))
                    .isInstanceOf(RuleAlreadyDeletedException.class);
        }
    }

    // Helper methods
    private RecurringRule createBasicRule() {
        return createBasicRule(Money.of(new BigDecimal("1000.00"), "PLN"));
    }

    private RecurringRule createBasicRule(Money amount) {
        return RecurringRule.create(
                new RecurringRuleId("RR10000001"),
                new CashFlowId("CF10000001"),
                UserId.of("U10000001"),
                new Name("Test Rule"),
                null,
                amount,
                Type.INFLOW,
                new CategoryName("TestCategory"),
                new MonthlyPattern(10, 1, false),
                LocalDate.of(2026, 3, 10),
                null,
                FIXED_NOW
        );
    }
}
```

#### RecurrencePattern Tests

```java
@DisplayName("Recurrence Patterns")
class RecurrencePatternTest {

    @Nested
    @DisplayName("MonthlyPattern")
    class MonthlyPatternTests {

        @Test
        @DisplayName("should calculate next occurrence from start date")
        void shouldCalculateNextOccurrenceFromStartDate() {
            // Given
            var pattern = new MonthlyPattern(10, 1, false);

            // When
            LocalDate next = pattern.nextOccurrenceFrom(LocalDate.of(2026, 3, 1));

            // Then
            assertThat(next).isEqualTo(LocalDate.of(2026, 3, 10));
        }

        @Test
        @DisplayName("should skip to next month if past day")
        void shouldSkipToNextMonthIfPastDay() {
            // Given
            var pattern = new MonthlyPattern(10, 1, false);

            // When
            LocalDate next = pattern.nextOccurrenceFrom(LocalDate.of(2026, 3, 15));

            // Then
            assertThat(next).isEqualTo(LocalDate.of(2026, 4, 10));
        }

        @Test
        @DisplayName("should adjust for short month when flag enabled")
        void shouldAdjustForShortMonthWhenFlagEnabled() {
            // Given - day 31, February
            var pattern = new MonthlyPattern(31, 1, true);

            // When
            LocalDate next = pattern.nextOccurrenceFrom(LocalDate.of(2026, 2, 1));

            // Then - should use Feb 28
            assertThat(next).isEqualTo(LocalDate.of(2026, 2, 28));
        }

        @Test
        @DisplayName("should use correct day in leap year February")
        void shouldUseCorrectDayInLeapYearFebruary() {
            // Given - day 29, leap year 2028
            var pattern = new MonthlyPattern(29, 1, true);

            // When
            LocalDate next = pattern.nextOccurrenceFrom(LocalDate.of(2028, 2, 1));

            // Then
            assertThat(next).isEqualTo(LocalDate.of(2028, 2, 29));
        }

        @Test
        @DisplayName("should handle interval of 2 months")
        void shouldHandleIntervalOfTwoMonths() {
            // Given
            var pattern = new MonthlyPattern(15, 2, false);

            // When - start from Jan 20, should get March 15
            LocalDate next = pattern.nextOccurrenceFrom(LocalDate.of(2026, 1, 20));

            // Then
            assertThat(next).isEqualTo(LocalDate.of(2026, 3, 15));
        }
    }

    @Nested
    @DisplayName("WeeklyPattern")
    class WeeklyPatternTests {

        @Test
        @DisplayName("should find next Monday")
        void shouldFindNextMonday() {
            // Given
            var pattern = new WeeklyPattern(DayOfWeek.MONDAY, 1);

            // When - Wednesday Feb 25, 2026
            LocalDate next = pattern.nextOccurrenceFrom(LocalDate.of(2026, 2, 25));

            // Then - Monday Mar 2
            assertThat(next).isEqualTo(LocalDate.of(2026, 3, 2));
        }

        @Test
        @DisplayName("should return same day if already on target day")
        void shouldReturnSameDayIfAlreadyOnTargetDay() {
            // Given
            var pattern = new WeeklyPattern(DayOfWeek.MONDAY, 1);

            // When - Monday Mar 2, 2026
            LocalDate next = pattern.nextOccurrenceFrom(LocalDate.of(2026, 3, 2));

            // Then - same day
            assertThat(next).isEqualTo(LocalDate.of(2026, 3, 2));
        }
    }

    @Nested
    @DisplayName("YearlyPattern")
    class YearlyPatternTests {

        @Test
        @DisplayName("should find next yearly occurrence")
        void shouldFindNextYearlyOccurrence() {
            // Given - June 15 each year
            var pattern = new YearlyPattern(6, 15);

            // When - start from Feb 2026
            LocalDate next = pattern.nextOccurrenceFrom(LocalDate.of(2026, 2, 1));

            // Then
            assertThat(next).isEqualTo(LocalDate.of(2026, 6, 15));
        }

        @Test
        @DisplayName("should skip to next year if past date")
        void shouldSkipToNextYearIfPastDate() {
            // Given - June 15 each year
            var pattern = new YearlyPattern(6, 15);

            // When - start from July 2026
            LocalDate next = pattern.nextOccurrenceFrom(LocalDate.of(2026, 7, 1));

            // Then
            assertThat(next).isEqualTo(LocalDate.of(2027, 6, 15));
        }
    }
}
```

---

## 3. API Tests (MockMvc)

### 3.1 Controller Tests

```java
@WebMvcTest(RecurringRulesController.class)
@Import({SecurityTestConfig.class, FixedClockConfig.class})
@DisplayName("RecurringRulesController API")
class RecurringRulesControllerTest {

    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2026-02-25T10:00:00Z[UTC]");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CommandGateway commandGateway;

    @MockBean
    private QueryGateway queryGateway;

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("POST /api/v1/recurring-rules")
    class CreateRule {

        @Test
        @DisplayName("should create rule with valid request")
        @WithMockUser(username = "testuser")
        void shouldCreateRuleWithValidRequest() throws Exception {
            // Given
            var request = new CreateRecurringRuleRequest(
                    "CF10000001",
                    "Wynagrodzenie",
                    "Pensja miesięczna",
                    new MoneyDto(new BigDecimal("8500.00"), "PLN"),
                    TransactionType.INFLOW,
                    "Salary",
                    new MonthlyPatternDto(10, 1, false),
                    LocalDate.of(2026, 3, 10),
                    null
            );

            var expectedResponse = new RecurringRuleResponse(
                    "RR10000001",
                    "CF10000001",
                    "Wynagrodzenie",
                    "Pensja miesięczna",
                    new MoneyDto(new BigDecimal("8500.00"), "PLN"),
                    TransactionType.INFLOW,
                    "Salary",
                    new MonthlyPatternDto(10, 1, false),
                    LocalDate.of(2026, 3, 10),
                    null,
                    RuleStatus.ACTIVE,
                    LocalDate.of(2026, 3, 10),
                    List.of(),
                    FIXED_NOW,
                    FIXED_NOW
            );

            when(commandGateway.send(any(CreateRecurringRuleCommand.class)))
                    .thenReturn(expectedResponse);

            // When & Then
            mockMvc.perform(post("/api/v1/recurring-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ruleId").value("RR10000001"))
                    .andExpect(jsonPath("$.name").value("Wynagrodzenie"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.nextOccurrence").value("2026-03-10"));

            verify(commandGateway).send(argThat(cmd ->
                    cmd.cashFlowId().equals("CF10000001") &&
                    cmd.name().equals("Wynagrodzenie")
            ));
        }

        @Test
        @DisplayName("should return 400 for validation errors")
        @WithMockUser(username = "testuser")
        void shouldReturn400ForValidationErrors() throws Exception {
            // Given - missing required fields
            var request = """
                {
                  "cashFlowId": "",
                  "name": "",
                  "amount": {
                    "amount": -100,
                    "currency": "X"
                  }
                }
                """;

            // When & Then
            mockMvc.perform(post("/api/v1/recurring-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RR001"))
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'amount.amount')]").exists());
        }

        @Test
        @DisplayName("should return 400 when category not found")
        @WithMockUser(username = "testuser")
        void shouldReturn400WhenCategoryNotFound() throws Exception {
            // Given
            var request = createValidRequest();

            when(commandGateway.send(any(CreateRecurringRuleCommand.class)))
                    .thenThrow(new CategoryNotFoundException("NonExistent", new CashFlowId("CF10000001")));

            // When & Then
            mockMvc.perform(post("/api/v1/recurring-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RR004"))
                    .andExpect(jsonPath("$.message").value(containsString("NonExistent")));
        }

        @Test
        @DisplayName("should return 503 when CashFlow service unavailable")
        @WithMockUser(username = "testuser")
        void shouldReturn503WhenCashFlowUnavailable() throws Exception {
            // Given
            var request = createValidRequest();

            when(commandGateway.send(any(CreateRecurringRuleCommand.class)))
                    .thenThrow(new CashFlowServiceUnavailableException("Service down"));

            // When & Then
            mockMvc.perform(post("/api/v1/recurring-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("RR401"))
                    .andExpect(header().exists("Retry-After"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/recurring-rules/{ruleId}")
    class GetRule {

        @Test
        @DisplayName("should return rule details")
        @WithMockUser(username = "testuser")
        void shouldReturnRuleDetails() throws Exception {
            // Given
            var response = createDetailedRuleResponse();
            when(queryGateway.send(any(GetRecurringRuleQuery.class)))
                    .thenReturn(response);

            // When & Then
            mockMvc.perform(get("/api/v1/recurring-rules/RR10000001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ruleId").value("RR10000001"))
                    .andExpect(jsonPath("$.amountChanges").isArray())
                    .andExpect(jsonPath("$.executionHistory").isArray());
        }

        @Test
        @DisplayName("should return 404 for non-existent rule")
        @WithMockUser(username = "testuser")
        void shouldReturn404ForNonExistentRule() throws Exception {
            // Given
            when(queryGateway.send(any(GetRecurringRuleQuery.class)))
                    .thenThrow(new RuleNotFoundException(new RecurringRuleId("RR99999999")));

            // When & Then
            mockMvc.perform(get("/api/v1/recurring-rules/RR99999999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RR101"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/recurring-rules/{ruleId}")
    class DeleteRule {

        @Test
        @DisplayName("should delete rule and return impact summary")
        @WithMockUser(username = "testuser")
        void shouldDeleteRuleAndReturnImpactSummary() throws Exception {
            // Given
            var response = new DeleteRuleResponse(
                    "RR10000001",
                    FIXED_NOW,
                    new AffectedTransactions(5, 0, 5, "All confirmed"),
                    "Rule deleted. 5 transactions preserved."
            );

            when(commandGateway.send(any(DeleteRecurringRuleCommand.class)))
                    .thenReturn(response);

            // When & Then
            mockMvc.perform(delete("/api/v1/recurring-rules/RR10000001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ruleId").value("RR10000001"))
                    .andExpect(jsonPath("$.affectedTransactions.preserved").value(5));
        }
    }

    // Helper methods
    private CreateRecurringRuleRequest createValidRequest() {
        return new CreateRecurringRuleRequest(
                "CF10000001",
                "Test Rule",
                null,
                new MoneyDto(new BigDecimal("1000.00"), "PLN"),
                TransactionType.INFLOW,
                "TestCategory",
                new MonthlyPatternDto(10, 1, false),
                LocalDate.of(2026, 3, 10),
                null
        );
    }
}
```

---

## 4. Integration Tests

### 4.1 Full Flow Integration Test

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(FixedClockConfig.class)
@DisplayName("Recurring Rules Full Integration")
class RecurringRulesIntegrationTest {

    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2026-02-25T10:00:00Z[UTC]");

    @Container
    static MongoDBContainer mongodb = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"));

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RecurringRuleRepository ruleRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    // Mock CashFlow service
    @MockBean
    private ResilientCashFlowHttpClient cashFlowClient;

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeEach
    void setUp() {
        ruleRepository.deleteAll();
        outboxRepository.deleteAll();

        // Setup CashFlow mock
        when(cashFlowClient.getCategories(any())).thenReturn(new CashFlowCategoriesResponse(
                "CF10000001",
                new Categories(
                        List.of(new CategoryInfo("Salary", null, false, FIXED_NOW, null)),
                        List.of(new CategoryInfo("Housing", null, false, FIXED_NOW, null))
                )
        ));
    }

    @Test
    @DisplayName("should create rule and publish event to Kafka")
    void shouldCreateRuleAndPublishEventToKafka() throws Exception {
        // Given
        var request = createValidRequest();

        // When
        ResponseEntity<RecurringRuleResponse> response = restTemplate.exchange(
                "/api/v1/recurring-rules",
                HttpMethod.POST,
                createAuthenticatedRequest(request),
                RecurringRuleResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ruleId()).startsWith("RR");

        // Verify persisted
        Optional<RecurringRule> persisted = ruleRepository
                .findById(new RecurringRuleId(response.getBody().ruleId()));
        assertThat(persisted).isPresent();

        // Verify outbox entry
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<OutboxDocument> outboxEntries = outboxRepository.findAll();
            assertThat(outboxEntries)
                    .hasSize(1)
                    .first()
                    .extracting(OutboxDocument::getEventType)
                    .isEqualTo("RuleCreatedEvent");
        });
    }

    @Test
    @DisplayName("should handle complete CRUD lifecycle")
    void shouldHandleCompleteCrudLifecycle() {
        // Create
        var createRequest = createValidRequest();
        ResponseEntity<RecurringRuleResponse> createResponse = restTemplate.exchange(
                "/api/v1/recurring-rules",
                HttpMethod.POST,
                createAuthenticatedRequest(createRequest),
                RecurringRuleResponse.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ruleId = createResponse.getBody().ruleId();

        // Read
        ResponseEntity<RecurringRuleResponse> getResponse = restTemplate.exchange(
                "/api/v1/recurring-rules/" + ruleId,
                HttpMethod.GET,
                createAuthenticatedRequest(null),
                RecurringRuleResponse.class
        );
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().name()).isEqualTo("Test Rule");

        // Update
        var updateRequest = new UpdateRecurringRuleRequest(
                "Updated Name", null, null, null, null, null, true
        );
        ResponseEntity<UpdateRuleResponse> updateResponse = restTemplate.exchange(
                "/api/v1/recurring-rules/" + ruleId,
                HttpMethod.PUT,
                createAuthenticatedRequest(updateRequest),
                UpdateRuleResponse.class
        );
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify update
        getResponse = restTemplate.exchange(
                "/api/v1/recurring-rules/" + ruleId,
                HttpMethod.GET,
                createAuthenticatedRequest(null),
                RecurringRuleResponse.class
        );
        assertThat(getResponse.getBody().name()).isEqualTo("Updated Name");

        // Delete
        ResponseEntity<DeleteRuleResponse> deleteResponse = restTemplate.exchange(
                "/api/v1/recurring-rules/" + ruleId,
                HttpMethod.DELETE,
                createAuthenticatedRequest(null),
                DeleteRuleResponse.class
        );
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify deleted (should still be readable but with DELETED status)
        RecurringRule deleted = ruleRepository.findById(new RecurringRuleId(ruleId)).orElseThrow();
        assertThat(deleted.getStatus()).isEqualTo(RuleStatus.DELETED);
    }

    @Test
    @DisplayName("should pause rule when category is archived via Kafka")
    void shouldPauseRuleWhenCategoryArchivedViaKafka() throws Exception {
        // Given - create a rule
        var createResponse = restTemplate.exchange(
                "/api/v1/recurring-rules",
                HttpMethod.POST,
                createAuthenticatedRequest(createValidRequest()),
                RecurringRuleResponse.class
        );
        String ruleId = createResponse.getBody().ruleId();

        // When - publish CategoryArchivedEvent
        String categoryArchivedEvent = """
            {
              "eventType": "CategoryArchivedEvent",
              "cashFlowId": "CF10000001",
              "categoryName": "Salary",
              "categoryType": "INFLOW",
              "archivedAt": "2026-02-25T15:00:00Z"
            }
            """;

        try (var producer = createKafkaProducer()) {
            producer.send(new ProducerRecord<>("cash_flow", "CF10000001", categoryArchivedEvent)).get();
        }

        // Then - rule should be paused
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            RecurringRule rule = ruleRepository.findById(new RecurringRuleId(ruleId)).orElseThrow();
            assertThat(rule.getStatus()).isEqualTo(RuleStatus.PAUSED);
            assertThat(rule.getPauseInfo().reason()).contains("archived");
        });
    }

    // Helper methods
    private CreateRecurringRuleRequest createValidRequest() {
        return new CreateRecurringRuleRequest(
                "CF10000001",
                "Test Rule",
                null,
                new MoneyDto(new BigDecimal("1000.00"), "PLN"),
                TransactionType.INFLOW,
                "Salary",
                new MonthlyPatternDto(10, 1, false),
                LocalDate.of(2026, 3, 10),
                null
        );
    }

    private <T> HttpEntity<T> createAuthenticatedRequest(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(generateTestToken());
        return new HttpEntity<>(body, headers);
    }
}
```

### 4.2 HTTP Actor Pattern (zgodnie z CLAUDE.md)

```java
/**
 * Actor pattern for Recurring Rules HTTP interactions.
 * Encapsulates all REST API calls for cleaner test code.
 */
public class RecurringRulesHttpActor {

    private final TestRestTemplate restTemplate;
    private final String baseUrl;
    private String authToken;

    public RecurringRulesHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port + "/api/v1/recurring-rules";
    }

    public RecurringRulesHttpActor withAuth(String token) {
        this.authToken = token;
        return this;
    }

    public RecurringRuleResponse createRule(CreateRecurringRuleRequest request) {
        ResponseEntity<RecurringRuleResponse> response = restTemplate.exchange(
                baseUrl,
                HttpMethod.POST,
                createRequest(request),
                RecurringRuleResponse.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("Expected 2xx but got " + response.getStatusCode());
        }

        return response.getBody();
    }

    public RecurringRuleResponse getRule(String ruleId) {
        ResponseEntity<RecurringRuleResponse> response = restTemplate.exchange(
                baseUrl + "/" + ruleId,
                HttpMethod.GET,
                createRequest(null),
                RecurringRuleResponse.class
        );
        return response.getBody();
    }

    public UpdateRuleResponse updateRule(String ruleId, UpdateRecurringRuleRequest request) {
        ResponseEntity<UpdateRuleResponse> response = restTemplate.exchange(
                baseUrl + "/" + ruleId,
                HttpMethod.PUT,
                createRequest(request),
                UpdateRuleResponse.class
        );
        return response.getBody();
    }

    public DeleteRuleResponse deleteRule(String ruleId) {
        ResponseEntity<DeleteRuleResponse> response = restTemplate.exchange(
                baseUrl + "/" + ruleId,
                HttpMethod.DELETE,
                createRequest(null),
                DeleteRuleResponse.class
        );
        return response.getBody();
    }

    public AddAmountChangeResponse addAmountChange(String ruleId, AddAmountChangeRequest request) {
        ResponseEntity<AddAmountChangeResponse> response = restTemplate.exchange(
                baseUrl + "/" + ruleId + "/amount-changes",
                HttpMethod.POST,
                createRequest(request),
                AddAmountChangeResponse.class
        );
        return response.getBody();
    }

    public ErrorResponse expectError(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected error but succeeded");
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            return new ObjectMapper().readValue(e.getResponseBodyAsString(), ErrorResponse.class);
        }
    }

    private <T> HttpEntity<T> createRequest(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authToken != null) {
            headers.setBearerAuth(authToken);
        }
        return new HttpEntity<>(body, headers);
    }
}
```

---

## 5. Test Scenarios Matrix

### 5.1 CRUD Operations

| Scenario | Test Type | Status |
|----------|-----------|--------|
| Create rule with valid data | Unit + API + Integration | ✅ |
| Create rule with missing fields | API | ✅ |
| Create rule with invalid pattern | Unit + API | ✅ |
| Create rule with non-existent category | Integration | ✅ |
| Create rule with archived category | Integration | ✅ |
| Create rule with category type mismatch | Integration | ✅ |
| Get rule by ID | API + Integration | ✅ |
| Get non-existent rule | API | ✅ |
| List rules with pagination | API + Integration | ✅ |
| List rules with filters | API | ✅ |
| Update rule name | Unit + API | ✅ |
| Update rule amount | Unit + API | ✅ |
| Update rule category | Integration | ✅ |
| Update deleted rule | Unit + API | ✅ |
| Delete rule (soft) | Unit + API + Integration | ✅ |
| Delete rule with transactions | Integration | ✅ |

### 5.2 Amount Changes

| Scenario | Test Type | Status |
|----------|-----------|--------|
| Add ONE_TIME change | Unit + API | ✅ |
| Add PERMANENT change | Unit + API | ✅ |
| Get effective amount (no changes) | Unit | ✅ |
| Get effective amount (ONE_TIME) | Unit | ✅ |
| Get effective amount (PERMANENT) | Unit | ✅ |
| Get effective amount (mixed) | Unit | ✅ |
| Add change with date conflict | Unit + API | ✅ |
| Add change before start date | Unit + API | ✅ |
| Remove amount change | Unit + API | ✅ |

### 5.3 Lifecycle

| Scenario | Test Type | Status |
|----------|-----------|--------|
| Pause active rule | Unit + API | ✅ |
| Pause with resume date | Unit | ✅ |
| Pause already paused rule | Unit + API | ✅ |
| Resume paused rule | Unit + API | ✅ |
| Resume with generate skipped | Integration | ✅ |
| Rule completion (end date reached) | Unit | ✅ |
| Auto-pause on category archive | Integration | ✅ |
| Complete on CashFlow close | Integration | ✅ |

### 5.4 Execution

| Scenario | Test Type | Status |
|----------|-----------|--------|
| Execute rule successfully | Integration | ✅ |
| Execute with amount change applied | Integration | ✅ |
| Idempotent execution | Integration | ✅ |
| Execution with CashFlow error | Integration | ✅ |
| Execution retry on failure | Integration | ✅ |
| Recovery of failed executions | Integration | ✅ |

### 5.5 Integration

| Scenario | Test Type | Status |
|----------|-----------|--------|
| Category validation via HTTP | Integration | ✅ |
| CashChange creation via HTTP | Integration | ✅ |
| Circuit breaker activation | Integration | ✅ |
| Kafka event publication | Integration | ✅ |
| Kafka event consumption | Integration | ✅ |
| Outbox processing | Integration | ✅ |

---

## 6. Test Configuration

### 6.1 FixedClockConfig

```java
@TestConfiguration
public class FixedClockConfig {

    public static final ZonedDateTime FIXED_TIME =
            ZonedDateTime.parse("2026-02-25T10:00:00Z[UTC]");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_TIME.toInstant(), ZoneId.of("UTC"));
    }
}
```

### 6.2 SecurityTestConfig

```java
@TestConfiguration
@EnableWebSecurity
public class SecurityTestConfig {

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
```

---

## Następny dokument

Przejdź do [08-inconsistencies-and-questions.md](./08-inconsistencies-and-questions.md) aby zobaczyć znalezione niespójności i pytania bez odpowiedzi.
