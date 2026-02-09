package com.multi.vidulum;

import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.app.CashFlowRestController;
import com.multi.vidulum.cashflow.app.commands.append.AppendExpectedCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.attest.MakeMonthlyAttestationCommand;
import com.multi.vidulum.cashflow.app.commands.comment.create.CreateCategoryCommand;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.trading.domain.IntegrationTest;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;
import static org.awaitility.Awaitility.await;

@Slf4j
public class CashFlowForecastStatementGenerator extends IntegrationTest {

    @Autowired
    private Actor actor;

    @Autowired
    private CommandGateway commandGateway;

    @Autowired
    private Clock clock;

    private static final int ITERATIONS_PER_PERIOD = 10;
    private static final int NUMBER_OF_ATTESTED_MONTHS = 8;

    @SneakyThrows
    @Test
    void test() {
        Random random = new Random();

        CashFlowId cashFlowId = actor.createCashFlow();
        log.info("generated cashflowId by actor: {}", cashFlowId);

        Map<CashChangeId, CashChangeStatus> statusMap = new HashMap<>();

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Category1"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Category1"), new CategoryName("Category101"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Category101"), new CategoryName("Category10001"), INFLOW);
        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Category2"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Category2"), new CategoryName("Category201"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Category2"), new CategoryName("Category202"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Category202"), new CategoryName("Category20002"), INFLOW);
        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Category3"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Category3"), new CategoryName("Category303"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Category303"), new CategoryName("Category30003"), OUTFLOW);


        Map<Type, List<CategoryName>> categoryMap = Map.of(
                INFLOW, List.of(new CategoryName("Uncategorized"), new CategoryName("Category1"), new CategoryName("Category2")),
                OUTFLOW, List.of(new CategoryName("Uncategorized"), new CategoryName("Category3"))
        );

        CashChangeId lastCashChangeId = CashChangeId.generate();
        CashChangeStatus statusOfLastCashChange = CashChangeStatus.PENDING;
        YearMonth processingPeriod = YearMonth.now(clock);
        for (int i = 0; i < 12+NUMBER_OF_ATTESTED_MONTHS; i++) {

            boolean isMonthMeantToBeAttested = i <= NUMBER_OF_ATTESTED_MONTHS;
            for (int iteration = 0; iteration < ITERATIONS_PER_PERIOD; iteration++) {

                Type type = random.nextBoolean() ? INFLOW : OUTFLOW;
                CategoryName categoryName = categoryMap.get(type).get(random.nextInt(categoryMap.get(type).size()));
                ZonedDateTime created = ZonedDateTime.now(clock).plusMonths(i);
                ZonedDateTime dueDate = ZonedDateTime.now(clock).plusMonths(i).plusDays(3);
                log.info("Suppose to append cash change: created {}, duedate {}", created, dueDate);
                lastCashChangeId = actor.appendExpectedCashChange(cashFlowId, categoryName, type, Money.of(random.nextInt(20) * 100, "USD"), created, dueDate);
                statusMap.put(lastCashChangeId, CashChangeStatus.PENDING);
                statusOfLastCashChange = CashChangeStatus.PENDING;
                boolean shouldConfirm = random.nextBoolean();
                if (shouldConfirm) {
                    actor.confirmCashChange(cashFlowId, lastCashChangeId);
                    statusMap.put(lastCashChangeId, CashChangeStatus.CONFIRMED);
                    statusOfLastCashChange = CashChangeStatus.CONFIRMED;
                }
            }
            processingPeriod = YearMonth.now(clock).plusMonths(i);
            if (isMonthMeantToBeAttested) {
                actor.attestMonth(cashFlowId, processingPeriod.plusMonths(1), Money.zero("USD"), processingPeriod.plusMonths(1).atEndOfMonth().atStartOfDay(ZoneOffset.UTC));
            }
        }

        CashChangeId finalLastCashChangeId = lastCashChangeId;
        CashChangeStatus finalStatusOfLastCashChange = statusOfLastCashChange;
        PaymentStatus expectedPaymentStatusOfLastCashChange = finalStatusOfLastCashChange == CashChangeStatus.CONFIRMED ? PaymentStatus.PAID : PaymentStatus.EXPECTED;

        await().until(() -> statementRepository.findByCashFlowId(cashFlowId).isPresent());

        YearMonth finalProcessingPeriod = processingPeriod;


        await().until(() -> cashChangeInStatusHasBeenProcessed(cashFlowId, finalProcessingPeriod, finalLastCashChangeId, expectedPaymentStatusOfLastCashChange));

        log.info("{}", JsonContent.asJson(statementRepository.findByCashFlowId(cashFlowId).get()).content());


    }

    protected boolean cashChangeInStatusHasBeenProcessed(CashFlowId cashFlowId, YearMonth period, CashChangeId cashChangeId, PaymentStatus paymentStatus) {
        return statementRepository.findByCashFlowId(cashFlowId)
                .map(CashFlowForecastStatement::getForecasts)
                .stream().map(yearMonthCashFlowMonthlyForecastMap -> yearMonthCashFlowMonthlyForecastMap.get(period))
                .anyMatch(cashFlowMonthlyForecast -> Stream.concat(
                                Optional.ofNullable(cashFlowMonthlyForecast.getCategorizedInFlows()).orElse(List.of()).stream(),
                                Optional.ofNullable(cashFlowMonthlyForecast.getCategorizedOutFlows()).orElse(List.of()).stream())
                        .map(cashCategory -> cashCategory.getGroupedTransactions().fetchTransaction(cashChangeId))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .anyMatch(transaction -> paymentStatus.equals(transaction.paymentStatus())));
    }


}


@Component
@AllArgsConstructor
class Actor {

    private CashFlowRestController cashFlowRestController;
    private CommandGateway commandGateway;

    CashFlowId createCashFlow() {
        return new CashFlowId(cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(CashFlowDto.BankAccountJson.from(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD"))))
                        .build()
        ));
    }

    CashChangeId appendExpectedCashChange(CashFlowId cashFlowId, CategoryName category, Type type, Money money, ZonedDateTime created, ZonedDateTime dueDate) {
        return commandGateway.send(
                new AppendExpectedCashChangeCommand(
                        cashFlowId,
                        category,
                        new CashChangeId(CashChangeId.generate().id()),
                        new Name("cash-change name"),
                        new Description("cash-change description"),
                        money,
                        type,
                        created,
                        dueDate
                ));
    }

    public void confirmCashChange(CashFlowId cashFlowId, CashChangeId cashChangeId1) {
        cashFlowRestController.confirm(
                CashFlowDto.ConfirmCashChangeJson.builder()
                        .cashFlowId(cashFlowId.id())
                        .cashChangeId(cashChangeId1.id())
                        .build()
        );
    }

    public void attestMonth(
            CashFlowId cashFlowId,
            YearMonth period,
            Money currentMoney,
            ZonedDateTime dateTime
    ) {
        commandGateway.send(new MakeMonthlyAttestationCommand(
                cashFlowId, period, currentMoney, dateTime
        ));
    }


    public void addCategory(CashFlowId cashFlowId,
                            CategoryName parentCategoryName,
                            CategoryName categoryName,
                            Type type) {
        commandGateway.send(new CreateCategoryCommand(
                cashFlowId,
                parentCategoryName,
                categoryName,
                type
        ));
    }

    /**
     * Add category during import operation (allowed in SETUP mode).
     */
    public void addCategoryForImport(CashFlowId cashFlowId,
                                      CategoryName parentCategoryName,
                                      CategoryName categoryName,
                                      Type type) {
        commandGateway.send(CreateCategoryCommand.forImport(
                cashFlowId,
                parentCategoryName,
                categoryName,
                type
        ));
    }
}