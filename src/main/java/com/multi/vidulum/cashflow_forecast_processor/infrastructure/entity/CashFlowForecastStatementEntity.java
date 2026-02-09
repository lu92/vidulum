package com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity;

import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowMonthlyForecast;
import com.multi.vidulum.cashflow_forecast_processor.app.CurrentCategoryStructure;
import com.multi.vidulum.common.Checksum;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@Builder
@Document("cash-flow-forecast-statement")
public class CashFlowForecastStatementEntity {

    @Id
    private String cashFlowId;
    private List<MonthlyForecastEntry> forecasts;
    private BankAccountNumberEntity bankAccountNumber;
    private CurrentCategoryStructureEntity categoryStructure;
    private Date lastModification;
    private String lastMessageChecksum;

    public static CashFlowForecastStatementEntity fromDomain(CashFlowForecastStatement statement) {
        if (statement == null) {
            return null;
        }

        List<MonthlyForecastEntry> forecastEntries = statement.getForecasts().entrySet().stream()
                .map(entry -> MonthlyForecastEntry.builder()
                        .period(entry.getKey().toString())
                        .forecast(CashFlowMonthlyForecastEntity.fromDomain(entry.getValue()))
                        .build())
                .collect(Collectors.toList());

        Date lastModificationDate = statement.getLastModification() != null
                ? Date.from(statement.getLastModification().toInstant())
                : null;

        String checksumValue = statement.getLastMessageChecksum() != null
                ? statement.getLastMessageChecksum().checksum()
                : null;

        return CashFlowForecastStatementEntity.builder()
                .cashFlowId(statement.getCashFlowId().id())
                .forecasts(forecastEntries)
                .bankAccountNumber(BankAccountNumberEntity.fromDomain(statement.getBankAccountNumber()))
                .categoryStructure(CurrentCategoryStructureEntity.fromDomain(statement.getCategoryStructure()))
                .lastModification(lastModificationDate)
                .lastMessageChecksum(checksumValue)
                .build();
    }

    public CashFlowForecastStatement toDomain() {
        Map<YearMonth, CashFlowMonthlyForecast> forecastMap = new LinkedHashMap<>();
        if (forecasts != null) {
            for (MonthlyForecastEntry entry : forecasts) {
                YearMonth period = YearMonth.parse(entry.getPeriod());
                forecastMap.put(period, entry.getForecast().toDomain());
            }
        }

        ZonedDateTime lastModificationDateTime = lastModification != null
                ? ZonedDateTime.ofInstant(lastModification.toInstant(), ZoneOffset.UTC)
                : null;

        Checksum checksum = lastMessageChecksum != null
                ? new Checksum(lastMessageChecksum)
                : null;

        return CashFlowForecastStatement.builder()
                .cashFlowId(new CashFlowId(cashFlowId))
                .forecasts(forecastMap)
                .bankAccountNumber(bankAccountNumber != null ? bankAccountNumber.toDomain() : null)
                .categoryStructure(categoryStructure != null ? categoryStructure.toDomain() : null)
                .lastModification(lastModificationDateTime)
                .lastMessageChecksum(checksum)
                .build();
    }

    @Data
    @Builder
    public static class MonthlyForecastEntry {
        private String period;
        private CashFlowMonthlyForecastEntity forecast;
    }

    @Data
    @Builder
    public static class BankAccountNumberEntity {
        private String account;
        private String denomination;

        public static BankAccountNumberEntity fromDomain(BankAccountNumber bankAccountNumber) {
            if (bankAccountNumber == null) {
                return null;
            }
            return BankAccountNumberEntity.builder()
                    .account(bankAccountNumber.account())
                    .denomination(bankAccountNumber.denomination().getId())
                    .build();
        }

        public BankAccountNumber toDomain() {
            return new BankAccountNumber(
                    account,
                    new com.multi.vidulum.common.Currency(denomination)
            );
        }
    }
}
