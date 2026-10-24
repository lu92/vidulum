package com.multi.vidulum.cashflow.api.view;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.common.BankAccountId;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tree operations the contract took over from {@code CashFlowInfo}.
 *
 * <p>They moved because <b>deciding what counts as "the same category" is cashflow's policy, not a
 * consumer's string handling</b>. The view is typed on purpose - a name read out of a bank
 * statement is not a {@link CategoryName} until something says it is - and
 * {@code findCategoryIgnoringCase} is that something. Before this, two handlers in
 * bank-data-ingestion lowercased and compared on their own, against a {@code Set<String>}.
 *
 * <p>The one-sided overload is the one production used, and these tests say why: a cash flow may
 * hold the same word in both directions.
 */
class CashFlowSkeletonViewTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");

    private static CategoryNodeView node(String name, CategoryNodeView... children) {
        return new CategoryNodeView(new CategoryName(name), null, false, List.of(children));
    }

    private static CategoryNodeView child(String parent, String name) {
        return new CategoryNodeView(new CategoryName(name), new CategoryName(parent), false, List.of());
    }

    /** "Transfer" on both sides, plus a nested expense tree. */
    private static CashFlowSkeletonView skeleton() {
        return new CashFlowSkeletonView(
                CASH_FLOW_ID,
                UserId.of("U10000001"),
                CashFlowSkeletonView.LifecyclePhase.SETUP,
                YearMonth.of(2022, 1),
                YearMonth.of(2021, 10),
                new BankAccountId("PL61109010140000071219812874"),
                Currency.of("PLN"),
                List.of(node("Salary", child("Salary", "Bonus")), node("Transfer")),
                List.of(node("Home", child("Home", "Rent"), child("Home", "Utilities")),
                        node("Transfer")));
    }

    @Test
    void findsACategoryWhateverCaseTheBankUsed() {
        assertThat(skeleton().findCategoryIgnoringCase("  rENT  "))
                .as("trimmed and case-folded, because a statement is not a catalogue")
                .contains(new CategoryName("Rent"));
    }

    @Test
    void answersWithTheStoredSpellingNotTheQueried() {
        assertThat(skeleton().findCategoryIgnoringCase("utilities"))
                .get()
                .extracting(CategoryName::name)
                .as("the cash flow's own spelling - which is the point of asking cashflow")
                .isEqualTo("Utilities");
    }

    @Test
    void findsNothingForABlankOrUnknownName() {
        assertThat(skeleton().findCategoryIgnoringCase("   ")).isEmpty();
        assertThat(skeleton().findCategoryIgnoringCase(null)).isEmpty();
        assertThat(skeleton().findCategoryIgnoringCase("Groceries")).isEmpty();
    }

    @Test
    void theOneSidedLookupDoesNotCrossDirections() {
        CashFlowSkeletonView skeleton = skeleton();

        assertThat(skeleton.findCategoryIgnoringCase("rent", Type.OUTFLOW))
                .contains(new CategoryName("Rent"));
        assertThat(skeleton.findCategoryIgnoringCase("rent", Type.INFLOW))
                .as("Rent is an expense; asking for it as income must not find it")
                .isEmpty();
        assertThat(skeleton.findCategoryIgnoringCase("transfer", Type.INFLOW))
                .as("the same word exists on both sides and each side has its own")
                .contains(new CategoryName("Transfer"));
    }

    @Test
    void findsTheParentOfANestedCategory() {
        assertThat(skeleton().parentOf(new CategoryName("rent"), Type.OUTFLOW))
                .as("case-insensitive, like the lookup next to it")
                .isEqualTo(new CategoryName("Home"));
        assertThat(skeleton().parentOf(new CategoryName("Bonus"), Type.INFLOW))
                .isEqualTo(new CategoryName("Salary"));
    }

    @Test
    void aTopLevelCategoryHasNoParent() {
        assertThat(skeleton().parentOf(new CategoryName("Home"), Type.OUTFLOW))
                .as("null means root - the encoding CategoryNodeView.isRoot() already uses")
                .isNull();
        assertThat(skeleton().parentOf(new CategoryName("Nonexistent"), Type.OUTFLOW))
                .as("and an unknown category is also null, which is why a caller that cares "
                        + "asks findCategoryIgnoringCase first")
                .isNull();
        assertThat(skeleton().parentOf(null, Type.OUTFLOW)).isNull();
    }

    @Test
    void typedNamesKeepTheTwoSidesApart() {
        assertThat(skeleton().typedCategoryNames())
                .as("subcategories included, and Transfer appears once per direction")
                .containsExactlyInAnyOrder(
                        new TypedCategoryName(new CategoryName("Salary"), Type.INFLOW),
                        new TypedCategoryName(new CategoryName("Bonus"), Type.INFLOW),
                        new TypedCategoryName(new CategoryName("Transfer"), Type.INFLOW),
                        new TypedCategoryName(new CategoryName("Home"), Type.OUTFLOW),
                        new TypedCategoryName(new CategoryName("Rent"), Type.OUTFLOW),
                        new TypedCategoryName(new CategoryName("Utilities"), Type.OUTFLOW),
                        new TypedCategoryName(new CategoryName("Transfer"), Type.OUTFLOW));
    }

    @Test
    void untypedNamesCollapseTheTwoSides() {
        assertThat(skeleton().allCategoryNames())
                .as("Transfer once, because a Set<CategoryName> cannot hold a direction - which is "
                        + "exactly why typedCategoryNames() exists")
                .containsExactlyInAnyOrder(
                        new CategoryName("Salary"), new CategoryName("Bonus"),
                        new CategoryName("Transfer"), new CategoryName("Home"),
                        new CategoryName("Rent"), new CategoryName("Utilities"));
    }

    // ─────────────────────────────── rachunek: isSameAccount

    @Test
    @DisplayName("ten sam rachunek rozpoznany we wszystkich czterech postaciach z prawdziwych danych")
    void shouldRecogniseTheSameAccountInEverySpellingTheDataContains() {
        CashFlowSkeletonView skeleton = skeleton();

        assertThat(skeleton.isSameAccount(new BankAccountId("PL61109010140000071219812874")))
                .as("IBAN, tak jak wpisuje go owner")
                .isTrue();
        assertThat(skeleton.isSameAccount(new BankAccountId("61109010140000071219812874")))
                .as("NRB bez prefiksu kraju, tak jak pisze go nagłówek wyciągu Nest")
                .isTrue();
        assertThat(skeleton.isSameAccount(new BankAccountId("PL 61 1090 1014 0000 0712 1981 2874")))
                .as("IBAN ze spacjami - BankAccountId je usuwa")
                .isTrue();
        assertThat(skeleton.isSameAccount(new BankAccountId("'61109010140000071219812874")))
                .as("apostrof z eksportu arkusza, tak jak w kolumnach Pekao")
                .isTrue();
    }

    @Test
    @DisplayName("inny rachunek to inny rachunek")
    void shouldRefuseADifferentAccount() {
        assertThat(skeleton().isSameAccount(new BankAccountId("PL98124014441111001078171074")))
                .as("rachunek Pekao wobec cash flow Nest - to jest dokładnie przypadek, który dziś przechodzi po cichu")
                .isFalse();
        assertThat(skeleton().isSameAccount(null)).isFalse();
    }

    @Test
    @DisplayName("dwa kraje nie kolidują, gdy oba numery mają prefiks")
    void shouldNotCollapseTwoCountriesWhenBothCarryAPrefix() {
        assertThat(skeleton().isSameAccount(new BankAccountId("DE61109010140000071219812874")))
                .as("ten sam numer krajowy, inny kraj - porównanie pełne, bo prefiks mają oba")
                .isFalse();
    }

    // ─────────────────────────────── waluta: refusalFor(Money)

    @Test
    @DisplayName("kwota w walucie cash flow przechodzi")
    void shouldAcceptMoneyInTheCashFlowCurrency() {
        assertThat(skeleton().refusalFor(Money.of(new BigDecimal("1234.50"), "PLN"))).isEmpty();
        assertThat(skeleton().accepts(Money.of(new BigDecimal("1234.50"), "pln")))
                .as("wielkość liter kodu waluty nie jest różnicą")
                .isTrue();
    }

    @Test
    @DisplayName("kwota w obcej walucie odmawiana z kodem i obiema walutami w komunikacie")
    void shouldRefuseMoneyInAForeignCurrency() {
        Optional<BusinessException> refusal = skeleton().refusalFor(Money.of(new BigDecimal("22.14"), "EUR"));

        assertThat(refusal).isPresent();
        assertThat(refusal.get().getErrorCode()).isEqualTo(ErrorCode.CASHFLOW_CURRENCY_MISMATCH);
        assertThat(refusal.get().getMessage())
                .contains("PLN")
                .contains("EUR")
                .contains(CASH_FLOW_ID.id());
    }

    @Test
    @DisplayName("brak kwoty nie jest błędem")
    void shouldAcceptNoMoneyAtAll() {
        assertThat(skeleton().refusalFor(null)).isEmpty();
    }

    // ─────────────────────────────── kontrakt rekordu

    @Test
    @DisplayName("rachunek i waluta są wymagane - cash flow bez rachunku nie istnieje")
    void shouldRequireAccountAndCurrency() {
        assertThatThrownBy(() -> new CashFlowSkeletonView(
                CASH_FLOW_ID, UserId.of("U10000001"), CashFlowSkeletonView.LifecyclePhase.SETUP,
                YearMonth.of(2022, 1), YearMonth.of(2021, 10), null,
                Currency.of("PLN"), List.of(), List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bankAccount");

        assertThatThrownBy(() -> new CashFlowSkeletonView(
                CASH_FLOW_ID, UserId.of("U10000001"), CashFlowSkeletonView.LifecyclePhase.SETUP,
                YearMonth.of(2022, 1), YearMonth.of(2021, 10),
                new BankAccountId("PL61109010140000071219812874"), null, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currency");

        assertThatThrownBy(() -> new CashFlowSkeletonView(
                CASH_FLOW_ID, UserId.of("U10000001"), CashFlowSkeletonView.LifecyclePhase.SETUP,
                YearMonth.of(2022, 1), YearMonth.of(2021, 10),
                new BankAccountId("PL61109010140000071219812874"), new Currency(), List.of(), List.of()))
                .as("Currency jest klasą Lomboka z konstruktorem bezargumentowym, więc puste Currency "
                        + "jest konstruowalne - i bez tego sprawdzenia wszystkie odmowy waluty "
                        + "wysypałyby się na NPE w equalsIgnoreCase")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currency id");
    }
}
