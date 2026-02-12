package com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity;

import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
public class CashFlowMonthlyForecastEntity {

    private String period;
    private CashFlowStatsEntity cashFlowStats;
    private List<CashCategoryEntity> categorizedInFlows;
    private List<CashCategoryEntity> categorizedOutFlows;
    private String status;
    private AttestationEntity attestation;

    public static CashFlowMonthlyForecastEntity fromDomain(CashFlowMonthlyForecast forecast) {
        if (forecast == null) {
            return null;
        }

        List<CashCategoryEntity> inflowEntities = forecast.getCategorizedInFlows() != null
                ? forecast.getCategorizedInFlows().stream()
                    .map(CashCategoryEntity::fromDomain)
                    .collect(Collectors.toList())
                : new LinkedList<>();

        List<CashCategoryEntity> outflowEntities = forecast.getCategorizedOutFlows() != null
                ? forecast.getCategorizedOutFlows().stream()
                    .map(CashCategoryEntity::fromDomain)
                    .collect(Collectors.toList())
                : new LinkedList<>();

        return CashFlowMonthlyForecastEntity.builder()
                .period(forecast.getPeriod().toString())
                .cashFlowStats(CashFlowStatsEntity.fromDomain(forecast.getCashFlowStats()))
                .categorizedInFlows(inflowEntities)
                .categorizedOutFlows(outflowEntities)
                .status(forecast.getStatus() != null ? forecast.getStatus().name() : null)
                .attestation(AttestationEntity.fromDomain(forecast.getAttestation()))
                .build();
    }

    public CashFlowMonthlyForecast toDomain() {
        List<CashCategory> inflowCategories = categorizedInFlows != null
                ? categorizedInFlows.stream()
                    .map(CashCategoryEntity::toDomain)
                    .collect(Collectors.toCollection(LinkedList::new))
                : new LinkedList<>();

        List<CashCategory> outflowCategories = categorizedOutFlows != null
                ? categorizedOutFlows.stream()
                    .map(CashCategoryEntity::toDomain)
                    .collect(Collectors.toCollection(LinkedList::new))
                : new LinkedList<>();

        return CashFlowMonthlyForecast.builder()
                .period(YearMonth.parse(period))
                .cashFlowStats(cashFlowStats != null ? cashFlowStats.toDomain() : null)
                .categorizedInFlows(inflowCategories)
                .categorizedOutFlows(outflowCategories)
                .status(status != null ? CashFlowMonthlyForecast.Status.valueOf(status) : null)
                .attestation(attestation != null ? attestation.toDomain() : null)
                .build();
    }

    @Data
    @Builder
    public static class CashFlowStatsEntity {
        private Money start;
        private Money end;
        private Money netChange;
        private CashSummaryEntity inflowStats;
        private CashSummaryEntity outflowStats;

        public static CashFlowStatsEntity fromDomain(CashFlowStats stats) {
            if (stats == null) {
                return null;
            }
            return CashFlowStatsEntity.builder()
                    .start(stats.getStart())
                    .end(stats.getEnd())
                    .netChange(stats.getNetChange())
                    .inflowStats(CashSummaryEntity.fromDomain(stats.getInflowStats()))
                    .outflowStats(CashSummaryEntity.fromDomain(stats.getOutflowStats()))
                    .build();
        }

        public CashFlowStats toDomain() {
            return new CashFlowStats(
                    start,
                    end,
                    netChange,
                    inflowStats != null ? inflowStats.toDomain() : null,
                    outflowStats != null ? outflowStats.toDomain() : null
            );
        }
    }

    @Data
    @Builder
    public static class CashSummaryEntity {
        private Money actual;
        private Money expected;
        private Money gapToForecast;

        public static CashSummaryEntity fromDomain(CashSummary summary) {
            if (summary == null) {
                return null;
            }
            return CashSummaryEntity.builder()
                    .actual(summary.actual())
                    .expected(summary.expected())
                    .gapToForecast(summary.gapToForecast())
                    .build();
        }

        public CashSummary toDomain() {
            return new CashSummary(actual, expected, gapToForecast);
        }
    }

    @Data
    @Builder
    public static class AttestationEntity {
        private Money bankAccountBalance;
        private String type;
        private Date dateTime;

        public static AttestationEntity fromDomain(Attestation attestation) {
            if (attestation == null) {
                return null;
            }
            return AttestationEntity.builder()
                    .bankAccountBalance(attestation.bankAccountBalance())
                    .type(attestation.type() != null ? attestation.type().name() : null)
                    .dateTime(attestation.dateTime() != null ? Date.from(attestation.dateTime().toInstant()) : null)
                    .build();
        }

        public Attestation toDomain() {
            ZonedDateTime dateTimeValue = dateTime != null
                    ? ZonedDateTime.ofInstant(dateTime.toInstant(), ZoneOffset.UTC)
                    : null;
            Attestation.Type typeValue = type != null ? Attestation.Type.valueOf(type) : null;
            return new Attestation(bankAccountBalance, typeValue, dateTimeValue);
        }
    }
}
