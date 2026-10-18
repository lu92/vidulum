package com.multi.vidulum.user_financial_profile.app;
import com.multi.vidulum.common.CashFlowId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens to the {@code cash_flow} Kafka topic and, on creation of a CashFlow, ensures
 * the bank account IBAN is registered in the user's financial profile.
 *
 * <p>Handles both event variants ({@link CashFlowEvent.CashFlowCreatedEvent} and
 * {@link CashFlowEvent.CashFlowWithHistoryCreatedEvent}) with a single idempotent
 * service call — {@link UserFinancialProfileService#claimOrLinkAccountForCashFlow}.
 *
 * <p>Other event types on the topic are ignored (the forecast processor handles them).
 */
@Slf4j
@Component
@AllArgsConstructor
public class UserFinancialProfileCashFlowListener {

    private final UserFinancialProfileService userFinancialProfileService;

    @KafkaListener(
            groupId = "owned_accounts_group",
            topics = "cash_flow",
            containerFactory = "cashFlowUnifiedEventContainerFactory"
    )
    public void on(CashFlowUnifiedEvent envelope) {
        String eventType = (String) envelope.getMetadata().get("event");
        switch (eventType) {
            case "CashFlowCreatedEvent", "CashFlowWithHistoryCreatedEvent" -> {
                CashFlowCreated e = envelope.getContent().to(CashFlowCreated.class);
                handleCashFlowCreation(e, eventType);
            }
            default -> log.debug("Ignoring cash_flow event of type [{}]", eventType);
        }
    }

    private void handleCashFlowCreation(CashFlowCreated event, String eventType) {
        log.info("Processing [{}] for owned-accounts registry: user=[{}], cashFlow=[{}]",
                eventType, event.userId().getId(), event.cashFlowId().id());
        userFinancialProfileService.claimOrLinkAccountForCashFlow(
                event.userId(),
                event.rawIban(),
                event.currency(),
                event.bankName(),
                event.cashFlowId());
    }

    /**
     * What this module reads off the {@code cash_flow} topic, and nothing more.
     *
     * <p><b>Its own view of somebody else's event, declared here on purpose.</b> This used to
     * deserialise {@code CashFlowEvent.CashFlowCreatedEvent} - a class from cashflow's domain
     * package - which was the last import keeping this module inside the cashflow artifact. A
     * payload on a topic is text; naming the producer's class to read it means a consumer cannot be
     * built, deployed or reasoned about without the producer's internals on its classpath.
     *
     * <p>{@code @JsonIgnoreProperties} is doing real work: the real event carries a name, a
     * description, both periods, an initial balance, timestamps and a checksum. Three values are
     * read here, so three are named.
     *
     * <p>One record for two event types, because this module treats them identically - a cash flow
     * created with history and one created empty both mean "this user owns this account". The
     * switch above says so in one case label rather than two blocks that called the same method.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CashFlowCreated(UserId userId, CashFlowId cashFlowId, BankAccount bankAccount) {

        String rawIban() {
            return bankAccount == null || bankAccount.bankAccountNumber() == null
                    ? null
                    : bankAccount.bankAccountNumber().rawIban();
        }

        String currency() {
            return bankAccount == null || bankAccount.bankAccountNumber() == null
                    ? null
                    : bankAccount.bankAccountNumber().denominationId();
        }

        String bankName() {
            return bankAccount == null || bankAccount.bankName() == null
                    ? null
                    : bankAccount.bankName().name();
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record BankAccount(BankName bankName, AccountNumber bankAccountNumber) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record BankName(String name) {
        }

        /**
         * The IBAN arrives nested - {@code {"iban": {"value": "PL61..."}, "denomination": {"id": "PLN"}}} -
         * because that is how cashflow's value objects serialise. Mirrored, not interpreted.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record AccountNumber(IbanValue iban, Denomination denomination) {
            String rawIban() {
                return iban == null ? null : iban.value();
            }

            String denominationId() {
                return denomination == null ? null : denomination.id();
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record IbanValue(String value) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Denomination(String id) {
        }
    }
}
