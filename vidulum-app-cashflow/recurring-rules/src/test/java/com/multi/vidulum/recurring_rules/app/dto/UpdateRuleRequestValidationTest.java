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
 * The same annotations as on the create request, on a class that had been left behind.
 *
 * <p>Create was fixed first and this one kept the defect: {@code pattern} carried {@code @NotNull}
 * and no {@code @Valid}, so a pattern object with no type passed validation and reached
 * {@code PatternDto.toPattern()}, whose {@code switch} answered with a
 * {@code NullPointerException} - a 500 for a request a reader can see is incomplete.
 *
 * <p>Its own test rather than a shared one, because the annotations belong to the class: a
 * constraint removed here would not be noticed by {@code CreateRuleRequestValidationTest}.
 */
class UpdateRuleRequestValidationTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    @Test
    void aPatternWithoutATypeIsRefused() {
        UpdateRuleRequest request = validRequest();
        request.setPattern(PatternDto.builder().dayOfMonth(15).intervalMonths(1).build());

        assertThat(violatedFields(request)).containsExactly("pattern.type");
    }

    /**
     * The difference from the create request, and the reason this list is spelled out: an update
     * names neither a user nor a cash flow. The rule already belongs to both, and
     * {@code UpdateRuleCommandHandler} reads the cash flow off the stored rule rather than off the
     * request - a rule cannot be moved between cash flows by editing it.
     */
    @Test
    void anEmptyRequestNamesEveryMissingFieldAndNoOthers() {
        assertThat(violatedFields(new UpdateRuleRequest()))
                .containsExactlyInAnyOrder("name", "baseAmount", "category", "pattern", "startDate");
    }

    @Test
    void aBlankNameIsRefusedJustLikeAMissingOne() {
        UpdateRuleRequest request = validRequest();
        request.setName("   ");

        assertThat(violatedFields(request)).containsExactly("name");
    }

    @Test
    void anOccurrenceLimitOfZeroIsRefusedWhileNoLimitIsFine() {
        UpdateRuleRequest withZero = validRequest();
        withZero.setMaxOccurrences(0);
        assertThat(violatedFields(withZero)).containsExactly("maxOccurrences");

        UpdateRuleRequest withNone = validRequest();
        withNone.setMaxOccurrences(null);
        assertThat(violatedFields(withNone))
                .as("absent means no limit, which is a legitimate rule")
                .isEmpty();
    }

    @Test
    void aCompleteRequestHasNothingToComplainAbout() {
        assertThat(violatedFields(validRequest())).isEmpty();
    }

    private static Set<String> violatedFields(UpdateRuleRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static UpdateRuleRequest validRequest() {
        return UpdateRuleRequest.builder()
                .name("Rent")
                .description("monthly rent")
                .baseAmount(Money.of(-1600, "PLN"))
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
