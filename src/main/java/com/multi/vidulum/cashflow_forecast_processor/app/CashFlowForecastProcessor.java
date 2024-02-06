package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastMongoRepository;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
@Component
@AllArgsConstructor
public class CashFlowForecastProcessor {

    private final CashFlowForecastMongoRepository repository;
    private final CashFlowForecastStatementRepository statementRepository = new CashFlowForecastStatementRepository.InMemory();
    private final Clock clock;

    void process(CashFlowEvent cashFlowEvent) {
        oldProcessing(cashFlowEvent);

        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(cashFlowEvent.cashFlowId())
                .orElseGet(CashFlowForecastStatement::new);


        statement = process(cashFlowEvent, statement);

        statementRepository.save(statement);

    }

    private CashFlowForecastStatement process(CashFlowEvent cashFlowEvent, CashFlowForecastStatement statement) {
        switch (cashFlowEvent) {
            case CashFlowEvent.CashFlowCreatedEvent event -> {
                YearMonth current = YearMonth.from(event.created());
                Map<YearMonth, CashFlowMonthlyForecast> monthlyForecasts = IntStream.rangeClosed(0, 12)
                        .mapToObj(current::plusMonths)
                        .map(yearMonth -> new CashFlowMonthlyForecast(
                                yearMonth,
                                CashFlowStats.justBalance(event.balance()),
                                List.of(
                                        CashCategory.builder()
                                                .category(new Category("unknown"))
                                                .subCategories(List.of())
                                                .transactions(Map.of(
                                                        PaymentStatus.PAID, new LinkedList<>(),
                                                        PaymentStatus.EXPECTED, new LinkedList<>(),
                                                        PaymentStatus.FORECAST, new LinkedList<>()
                                                ))
                                                .totalValue(Money.zero(event.balance().getCurrency()))
                                                .build()
                                ),
                                List.of(
                                        CashCategory.builder()
                                                .category(new Category("unknown"))
                                                .subCategories(List.of())
                                                .transactions(Map.of(
                                                        PaymentStatus.PAID, new LinkedList<>(),
                                                        PaymentStatus.EXPECTED, new LinkedList<>(),
                                                        PaymentStatus.FORECAST, new LinkedList<>()
                                                ))
                                                .totalValue(Money.zero(event.balance().getCurrency()))
                                                .build()
                                )
                        )).collect(Collectors.toMap(
                                CashFlowMonthlyForecast::getPeriod,
                                Function.identity()
                        ));
                statement.setCashFlowId(event.cashFlowId());
                statement.setForecasts(monthlyForecasts);
                return statement;
            }

            case CashFlowEvent.CashChangeAppendedEvent event -> {
                YearMonth yearMonth = YearMonth.from(event.created());
                statement.getForecasts().compute(yearMonth, (yearMonth1, cashFlowMonthlyForecast) -> {

                    // for now there is only one 'unknown' category for both inflow/outflow
                    CashCategory unknownCashCategory;
                    if (Type.INFLOW.equals(event.type())) {
                        unknownCashCategory = cashFlowMonthlyForecast.getCategorizedInFlows().get(0);
                        CashSummary inflowCashSummary = cashFlowMonthlyForecast.getCashFlowStats().getInflowStats();
                        // update stats
                        cashFlowMonthlyForecast.getCashFlowStats().setInflowStats(
                                new CashSummary(
                                        inflowCashSummary.actual(),
                                        inflowCashSummary.expected().plus(event.money()),
                                        inflowCashSummary.gapToForecast()
                                )
                        );
                    } else {
                        unknownCashCategory = cashFlowMonthlyForecast.getCategorizedOutFlows().get(0);
                        CashSummary outflowCashSummary = cashFlowMonthlyForecast.getCashFlowStats().getOutflowStats();
                        // update stats
                        cashFlowMonthlyForecast.getCashFlowStats().setOutflowStats(
                                new CashSummary(
                                        outflowCashSummary.actual(),
                                        outflowCashSummary.expected().plus(event.money()),
                                        outflowCashSummary.gapToForecast()
                                )
                        );
                    }
                    unknownCashCategory.getTransactions().get(PaymentStatus.EXPECTED)
                            .add(
                                    new TransactionDetails(
                                            event.cashChangeId(),
                                            event.name(),
                                            event.money(),
                                            event.created(),
                                            event.dueDate(),
                                            null
                                    )
                            );


                    return cashFlowMonthlyForecast;
                });
                return statement;
            }

            case CashFlowEvent.CashChangeConfirmedEvent event -> {
                CashFlowMonthlyForecast.CashChangeLocation cashChangeLocation = statement.locate(event.cashChangeId())
                        .orElseThrow(() -> new IllegalStateException(
                                String.format("Cannot find CashChange with id[%s]", event.cashChangeId())));

                statement.getForecasts().compute(cashChangeLocation.yearMonth(), (yearMonth1, cashFlowMonthlyForecast) -> {

                    // dowiedziec sie czy event jest dla inflow czy outflow
                    // zwiekszyc albo zmniejszyc actual money
                    // przesunac transaction details do paid sekcji

                    TransactionDetails transactionDetails = Stream.concat(
                                    cashFlowMonthlyForecast.getCategorizedInFlows().stream(),
                                    cashFlowMonthlyForecast.getCategorizedOutFlows().stream())
                            .map(cashCategory -> cashCategory.getTransactions().values())
                            .flatMap(Collection::stream)
                            .flatMap(Collection::stream)
                            .filter(transaction -> event.cashChangeId().equals(transaction.getCashChangeId()))
                            .findFirst()
                            .orElseThrow(() -> new CashChangeDoesNotExistsException(event.cashChangeId()));

                    if (Type.INFLOW.equals(cashChangeLocation.type())) {
                        CashSummary inflowStats = cashFlowMonthlyForecast.getCashFlowStats().getInflowStats();
                        cashFlowMonthlyForecast.getCashFlowStats().setInflowStats(
                                new CashSummary(
                                        inflowStats.actual().plus(transactionDetails.getMoney()),
                                        inflowStats.expected().minus(transactionDetails.getMoney()),
                                        inflowStats.gapToForecast()
                                )
                        );

                        cashFlowMonthlyForecast.getCategorizedInFlows()
                                .get(0)
                                .getTransactions().get(PaymentStatus.EXPECTED)
                                .remove(transactionDetails);

                        cashFlowMonthlyForecast.getCategorizedInFlows()
                                .get(0)
                                .getTransactions().get(PaymentStatus.PAID)
                                .add(new TransactionDetails(
                                        transactionDetails.getCashChangeId(),
                                        transactionDetails.getName(),
                                        transactionDetails.getMoney(),
                                        transactionDetails.getCreated(),
                                        transactionDetails.getDueDate(),
                                        event.endDate()
                                ));
                    } else {
                        CashSummary outflowStats = cashFlowMonthlyForecast.getCashFlowStats().getOutflowStats();
                        cashFlowMonthlyForecast.getCashFlowStats().setOutflowStats(
                                new CashSummary(
                                        outflowStats.actual().plus(transactionDetails.getMoney()),
                                        outflowStats.expected().minus(transactionDetails.getMoney()),
                                        outflowStats.gapToForecast()
                                )
                        );

                        cashFlowMonthlyForecast.getCategorizedInFlows()
                                .get(0)
                                .getTransactions().get(PaymentStatus.EXPECTED)
                                .remove(transactionDetails);

                        cashFlowMonthlyForecast.getCategorizedInFlows()
                                .get(0)
                                .getTransactions().get(PaymentStatus.PAID)
                                .add(new TransactionDetails(
                                        transactionDetails.getCashChangeId(),
                                        transactionDetails.getName(),
                                        transactionDetails.getMoney(),
                                        transactionDetails.getCreated(),
                                        transactionDetails.getDueDate(),
                                        event.endDate()
                                ));
                    }


                    return cashFlowMonthlyForecast;
                });
                return statement;
            }

            default -> throw new IllegalStateException("Unexpected value: " + cashFlowEvent);
        }
    }

    private void oldProcessing(CashFlowEvent cashFlowEvent) {
        CashFlowForecastEntity.Processing processing = new CashFlowForecastEntity.Processing(
                cashFlowEvent.getClass().getSimpleName(),
                JsonContent.asJson(cashFlowEvent).content());

        CashFlowForecastEntity cashFlowForecastEntity = repository.findByCashFlowId(cashFlowEvent.cashFlowId().id())
                .map(entity -> {
                    entity.getEvents().add(processing);
                    return entity;
                })
                .orElseGet(() -> {
                    LinkedList<CashFlowForecastEntity.Processing> events = new LinkedList<>();
                    events.add(processing);
                    return CashFlowForecastEntity.builder()
                            .cashFlowId(cashFlowEvent.cashFlowId().id())
                            .events(events)
                            .build();
                });
        CashFlowForecastEntity savedCashFlowForecast = repository.save(cashFlowForecastEntity);
        log.info("saved [{}]", savedCashFlowForecast);
    }

}
