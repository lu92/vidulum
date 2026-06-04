package com.multi.vidulum.user_financial_profile.app;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
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
            case "CashFlowCreatedEvent" -> {
                CashFlowEvent.CashFlowCreatedEvent e = envelope.getContent()
                        .to(CashFlowEvent.CashFlowCreatedEvent.class);
                handleCashFlowCreation(e.userId(), e.bankAccount(), e.cashFlowId(), eventType);
            }
            case "CashFlowWithHistoryCreatedEvent" -> {
                CashFlowEvent.CashFlowWithHistoryCreatedEvent e = envelope.getContent()
                        .to(CashFlowEvent.CashFlowWithHistoryCreatedEvent.class);
                handleCashFlowCreation(e.userId(), e.bankAccount(), e.cashFlowId(), eventType);
            }
            default -> log.debug("Ignoring cash_flow event of type [{}]", eventType);
        }
    }

    private void handleCashFlowCreation(
            com.multi.vidulum.common.UserId userId,
            com.multi.vidulum.cashflow.domain.BankAccount bankAccount,
            com.multi.vidulum.cashflow.domain.CashFlowId cashFlowId,
            String eventType
    ) {
        log.info("Processing [{}] for owned-accounts registry: user=[{}], cashFlow=[{}]",
                eventType, userId.getId(), cashFlowId.id());
        userFinancialProfileService.claimOrLinkAccountForCashFlow(userId, bankAccount, cashFlowId);
    }
}
