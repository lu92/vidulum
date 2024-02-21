package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class MonthAttestedEventHandler implements CashFlowEventHandler<CashFlowEvent.MonthAttestedEvent> {

    @Override
    public void handle(CashFlowEvent.MonthAttestedEvent event) {
        System.out.println();
    }
}
