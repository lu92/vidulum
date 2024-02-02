package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastMongoRepository;
import com.multi.vidulum.common.JsonContent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedList;

@Slf4j
@Component
@AllArgsConstructor
public class CashFlowForecastProcessor {

    private final CashFlowForecastMongoRepository repository;

    void process(CashFlowEvent cashFlowEvent) {
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
