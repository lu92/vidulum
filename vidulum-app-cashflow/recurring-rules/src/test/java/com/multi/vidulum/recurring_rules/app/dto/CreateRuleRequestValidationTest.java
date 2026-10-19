package com.multi.vidulum.recurring_rules.app.dto;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.RecurrenceType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the create request refuses before anything runs.
 *
 * <p>The annotations are a property of the record, so they are asserted here rather than through a
 * server: a real {@code jakarta} validator, no Spring, no Docker. What only HTTP can show - that
 * this becomes a 400 carrying {@code VALIDATION_ERROR} and the field names - is asserted in
 * {@code RecurringRulesHttpIntegrationTest}.
 */
class CreateRuleRequestValidationTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    /**
     * The case that used to be a 500. {@code PatternDto.toPattern()} switches on the type, so a
     * null left the controller with a {@code NullPointerException} while building the command -
     * an incomplete request answered as a server fault.
     *
     * <p>This also pins {@code @Valid} on the field, not just {@code @NotNull} on the type: bean
     * validation does not descend into a nested object unless told to, so without the former this
     * violation does not exist.
     */
    @Test
    void aPatternWithoutATypeIsRefused() {
        CreateRuleRequest request = validRequest();
        request.setPattern(PatternDto.builder().dayOfMonth(15).intervalMonths(1).build());

        assertThat(violatedFields(request))
                .containsExactly("pattern.type");
    }

    @Test
    void anEmptyRequestNamesEveryMissingField() {
        assertThat(violatedFields(new CreateRuleRequest()))
                .containsExactlyInAnyOrder(
                        "userId", "cashFlowId", "name", "baseAmount", "category",
                        "pattern", "startDate");
    }

    @Test
    void aBlankNameIsRefusedJustLikeAMissingOne() {
        CreateRuleRequest request = validRequest();
        request.setName("   ");

        assertThat(violatedFields(request))
                .as("blank is not absent, which is why these fields are @NotBlank")
                .containsExactly("name");
    }

    @Test
    void anOccurrenceLimitOfZeroIsRefusedWhileNoLimitIsFine() {
        CreateRuleRequest withZero = validRequest();
        withZero.setMaxOccurrences(0);
        assertThat(violatedFields(withZero)).containsExactly("maxOccurrences");

        CreateRuleRequest withNone = validRequest();
        withNone.setMaxOccurrences(null);
        assertThat(violatedFields(withNone))
                .as("absent means no limit, which is a legitimate rule")
                .isEmpty();
    }

    @Test
    void aCompleteRequestHasNothingToComplainAbout() {
        assertThat(violatedFields(validRequest())).isEmpty();
    }

    /**
     * The date range and the per-type pattern ranges are deliberately absent from this record's
     * annotations: {@code startDate} after {@code endDate} is the command handler's refusal
     * ({@code InvalidDateRangeException}) and a day-of-month of 32 is the domain's
     * ({@code MonthlyPattern} throws on construction). Checking them here as well would be a
     * second copy of a rule that already exists.
     */
    @Test
    void itDoesNotTakeOverTheChecksThatBelongElsewhere() {
        CreateRuleRequest backwardsRange = validRequest();
        backwardsRange.setStartDate(LocalDate.of(2022, 12, 31));
        backwardsRange.setEndDate(LocalDate.of(2022, 1, 1));

        assertThat(violatedFields(backwardsRange)).isEmpty();
    }

    private static Set<String> violatedFields(CreateRuleRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static CreateRuleRequest validRequest() {
        return CreateRuleRequest.builder()
                .userId("U10000001")
                .cashFlowId("CF10000001")
                .name("Rent")
                .description("monthly rent")
                .baseAmount(Money.of(-1500, "PLN"))
                .category("Housing")
                .pattern(PatternDto.builder()
                        .type(RecurrenceType.MONTHLY)
                        .dayOfMonth(15)
                        .intervalMonths(1)
                        .build())
                .startDate(LocalDate.of(2022, 1, 15))
                .build();
    }
}
