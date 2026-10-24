package com.multi.vidulum.cashflow.api.view;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.error.CurrencyMismatchException;
import com.multi.vidulum.common.BankAccountId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.BusinessException;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How a cash flow is put together, without what is in it.
 *
 * <p>Nine fields. The aggregate's snapshot has sixteen, one of which is a map of every
 * transaction; this view exists so that asking "how is this cash flow organised" costs the same
 * whether the answer concerns a cash flow opened today or one with four years of imports behind it.
 *
 * <p><b>The account is here; the balance is not, and the line between them is the point.</b> Which
 * account a cash flow is about, and in which currency, is part of how it is put together - it is
 * decided once and never changes, and it is what lets a consumer tell whether a bank statement is
 * even about this cash flow. The balance is what is <i>in</i> it: it moves with every import, so a
 * skeleton fetched once per upload would start lying immediately, and a consumer holding it would be
 * invited to do the attestation arithmetic itself, which is cashflow's business. Both additions are
 * O(1), so the property above still holds.
 *
 * <p>There is no direction field on a category and no {@code Type} anywhere in this contract.
 * Direction is which of the two lists a category is in - the only representation that cannot
 * contradict itself - and keeping it that way is also what frees this module from depending on
 * cashflow's domain enum, which 38 files of bank-data-ingestion share today.
 */
public record CashFlowSkeletonView(
        CashFlowId cashFlowId,
        UserId owner,
        LifecyclePhase phase,
        YearMonth activePeriod,
        YearMonth startPeriod,
        /**
         * The account this cash flow is about.
         *
         * <p>{@link BankAccountId} rather than cashflow's own {@code BankAccountNumber}, on purpose:
         * that one validates IBANs through iban4j, so putting it here would make this contract drag
         * iban4j along and <b>re-validate a stored number on every read</b> - the failure mode
         * user_financial_profile already lived through, where one stored IBAN that iban4j would
         * reject today made a whole profile unreadable. {@code BankAccountId} is a carrier that
         * normalises whitespace and case, which is all a comparison needs.
         */
        BankAccountId bankAccount,
        /**
         * The currency this cash flow's money is in - never the balance itself.
         *
         * <p><b>Taken from the balance, not from {@code BankAccountNumber.denomination}, and that is
         * a deliberate choice between two plausible sources.</b> {@code BankAccount.fromIban} accepts
         * the denomination and the initial balance as two independent parameters and enforces no
         * agreement between them, so they can disagree. The rule this field serves protects the
         * balance arithmetic - {@code Money.plus} keeps the left operand's currency and adds the
         * right one's amount - so the honest source is the currency that arithmetic actually uses.
         * Reading the denomination instead would let a row through that the sum would still mangle.
         */
        Currency currency,
        List<CategoryNodeView> inflowCategories,
        List<CategoryNodeView> outflowCategories) {

    /**
     * Where the cash flow is in its life.
     *
     * <p>The contract's own vocabulary, not the aggregate's nested {@code CashFlow.CashFlowStatus}.
     * Reusing that one would drag the aggregate into every consumer; and the vocabulary already
     * exists in three copies - in the aggregate, in the web DTO and in bank-data-ingestion's own
     * {@code CashFlowInfo} - so a fourth that is nobody's internal detail is the one worth having.
     */
    public enum LifecyclePhase {
        /** Historical import still running; the cash flow is not answering for money yet. */
        SETUP,
        /** In service. */
        OPEN,
        /** Finished; nothing new is appended. */
        CLOSED
    }

    public CashFlowSkeletonView {
        Objects.requireNonNull(cashFlowId, "cashFlowId is required");
        Objects.requireNonNull(owner, "owner is required");
        Objects.requireNonNull(phase, "phase is required");
        Objects.requireNonNull(bankAccount, "bankAccount is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(currency.getId(), "currency id is required");
        inflowCategories = inflowCategories == null ? List.of() : List.copyOf(inflowCategories);
        outflowCategories = outflowCategories == null ? List.of() : List.copyOf(outflowCategories);
    }

    public boolean isInSetup() {
        return phase == LifecyclePhase.SETUP;
    }

    public boolean isOpen() {
        return phase == LifecyclePhase.OPEN;
    }

    public boolean isClosed() {
        return phase == LifecyclePhase.CLOSED;
    }

    /**
     * Which dates this cash flow will accept an import for.
     *
     * <p>Three of this view's own fields are all the rule needs, which is why the rule can travel
     * with the view instead of being a question asked per row. See {@link ImportWindow} for what it
     * does and does not decide.
     */
    public ImportWindow importWindow() {
        return new ImportWindow(cashFlowId, phase, activePeriod, startPeriod);
    }

    /**
     * Whether {@code candidate} is this cash flow's account.
     *
     * <p><b>A method rather than a field comparison, because the same account arrives in different
     * shapes and deciding which of them are "the same" is cashflow's policy</b> - the same argument
     * that makes {@link #findCategoryIgnoringCase} a method. Measured on two real statements:
     *
     * <pre>
     * 93187010452083105656550001    26 digits  - a Polish NRB, as the bank's own header writes it
     * '98124014441111001078171074  26 digits  - the same shape, quoted by a spreadsheet export
     * PL61109010140000071219812874  28 chars   - an IBAN, as an owner types it
     * </pre>
     *
     * {@link BankAccountId} normalises whitespace and case but not the country prefix, so
     * {@code equals} on two correct spellings of one account answers {@code false}. Hence: anything
     * that is not a letter or a digit goes first, and <b>when exactly one side carries a country
     * prefix it is dropped before comparing</b>. When both carry one, they are compared in full - so
     * two different countries never collide just because their national numbers match.
     *
     * @param candidate as the source system spelled it; {@code null} matches nothing
     */
    public boolean isSameAccount(BankAccountId candidate) {
        if (candidate == null) {
            return false;
        }
        String mine = alphanumeric(bankAccount.value());
        String theirs = alphanumeric(candidate.value());
        if (hasCountryPrefix(mine) == hasCountryPrefix(theirs)) {
            return mine.equals(theirs);
        }
        return withoutCountryPrefix(mine).equals(withoutCountryPrefix(theirs));
    }

    /**
     * Why this cash flow would refuse {@code money}, or empty if it would accept it.
     *
     * <p>Same shape as {@link ImportWindow#refusalFor}: the failure is handed back rather than
     * thrown, because the two callers want different things from it - a write throws it, a consumer
     * staging 791 rows records its {@code ErrorCode} against one row and carries on with the rest.
     *
     * <p><b>One condition only, and it is the one that silently corrupts money.</b>
     * {@code Money.plus} keeps the currency of the left operand and adds the amount of the right,
     * with no check - so a cash flow in PLN absorbs 22.14 EUR as 22.14 PLN. Refusing is also the
     * right answer rather than merely the safe one: measured on a real statement, the two
     * foreign-currency rows have <b>no PLN counterpart</b>, so they are genuine transactions the
     * bank did not convert, not informational copies of a settlement.
     *
     * @param money {@code null} is accepted - a caller with nothing to record is not an error here
     */
    public Optional<BusinessException> refusalFor(Money money) {
        if (money == null || money.getCurrency() == null) {
            return Optional.empty();
        }
        return currency.getId().equalsIgnoreCase(money.getCurrency())
                ? Optional.empty()
                : Optional.of(new CurrencyMismatchException(
                        cashFlowId, currency, Currency.of(money.getCurrency())));
    }

    /** {@link #refusalFor}, for callers that only need the verdict. */
    public boolean accepts(Money money) {
        return refusalFor(money).isEmpty();
    }

    private static String alphanumeric(String account) {
        return account == null ? "" : account.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    private static boolean hasCountryPrefix(String account) {
        return account.length() > 2
                && Character.isLetter(account.charAt(0))
                && Character.isLetter(account.charAt(1));
    }

    private static String withoutCountryPrefix(String account) {
        return hasCountryPrefix(account) ? account.substring(2) : account;
    }

    /**
     * Every category name in the cash flow, subcategories included.
     *
     * <p>Typed, so that a name read out of a bank statement is not the same thing as a category
     * until someone says it is. The three call sites that compute this today do it over strings.
     */
    public Set<CategoryName> allCategoryNames() {
        return Stream.concat(inflowCategories.stream(), outflowCategories.stream())
                .flatMap(CategoryNodeView::flatten)
                .map(CategoryNodeView::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Names on the inflow side only - a cash flow may use one word for both directions. */
    public Set<CategoryName> inflowCategoryNames() {
        return namesOf(inflowCategories);
    }

    /** Names on the outflow side only. */
    public Set<CategoryName> outflowCategoryNames() {
        return namesOf(outflowCategories);
    }

    /**
     * The category whose name matches {@code rawName} ignoring case, on either side.
     *
     * <p><b>This is the moment a string becomes a category.</b> The view is typed on purpose - a
     * name read out of a bank statement is not a {@link CategoryName} until something says it is -
     * and this method is that something. It lives here rather than in a consumer because deciding
     * what counts as "the same category" is cashflow's policy: today that means trimming and
     * ignoring case, and if it ever means more (accents, punctuation, synonyms) every consumer
     * should start agreeing with it on the same day.
     *
     * <p>Searches both directions, like the {@code CashFlowInfo} helper it replaces. For a name
     * used on both sides the result is whichever is found first, which is the same thing the old
     * code did with a {@code Set<String>} - and the reason a caller that cares about direction
     * should use {@link #typedCategoryNames()} instead.
     *
     * @param rawName as the source system spelled it; blank or {@code null} matches nothing
     */
    public Optional<CategoryName> findCategoryIgnoringCase(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawName.trim().toLowerCase();
        return allCategoryNames().stream()
                .filter(name -> name.name().toLowerCase().equals(normalized))
                .findFirst();
    }

    /**
     * The same lookup restricted to one side.
     *
     * <p>The overload a caller should prefer, and the one production actually used: a cash flow may
     * hold "Transfer" as both income and expense, so matching without a direction can hand back
     * the wrong one. {@link #findCategoryIgnoringCase(String)} exists for callers that genuinely do
     * not care.
     */
    public Optional<CategoryName> findCategoryIgnoringCase(String rawName, Type type) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawName.trim().toLowerCase();
        return (type == Type.INFLOW ? inflowCategoryNames() : outflowCategoryNames()).stream()
                .filter(name -> name.name().toLowerCase().equals(normalized))
                .findFirst();
    }

    /**
     * The parent of a category on the given side, or {@code null} when it sits at the top.
     *
     * <p>{@code null} for a root is the encoding {@link CategoryNodeView#isRoot()} already uses, so
     * the two halves of this contract agree. Matching ignores case, for the same reason
     * {@link #findCategoryIgnoringCase} does: the caller is usually holding a name a bank wrote.
     *
     * @return the parent's name, or {@code null} if the category is top-level or does not exist -
     *         which a caller distinguishes with {@link #findCategoryIgnoringCase} if it matters
     */
    public CategoryName parentOf(CategoryName categoryName, Type type) {
        if (categoryName == null) {
            return null;
        }
        List<CategoryNodeView> roots = type == Type.INFLOW ? inflowCategories : outflowCategories;
        return parentOf(categoryName.name().toLowerCase(), roots, null);
    }

    private static CategoryName parentOf(String normalized, List<CategoryNodeView> nodes, CategoryName parent) {
        for (CategoryNodeView node : nodes) {
            if (node.name().name().toLowerCase().equals(normalized)) {
                return parent;
            }
            CategoryName found = parentOf(normalized, node.subCategories(), node.name());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Every category in the cash flow as a (name, direction) pair, subcategories included.
     *
     * <p>What {@link #allCategoryNames()} is for callers that must not confuse the two sides - a
     * cash flow may well have "Transfer" in both.
     */
    public Set<TypedCategoryName> typedCategoryNames() {
        return Stream.concat(
                        inflowCategories.stream().flatMap(CategoryNodeView::flatten)
                                .map(node -> new TypedCategoryName(node.name(), Type.INFLOW)),
                        outflowCategories.stream().flatMap(CategoryNodeView::flatten)
                                .map(node -> new TypedCategoryName(node.name(), Type.OUTFLOW)))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<CategoryName> namesOf(List<CategoryNodeView> roots) {
        return roots.stream()
                .flatMap(CategoryNodeView::flatten)
                .map(CategoryNodeView::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
