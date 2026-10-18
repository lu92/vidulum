package com.multi.vidulum.cashflow_forecast_processor.infrastructure;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Builder
@ToString
@Document("cash-flow-forecast-document")
public class CashFlowForecastEntity {
    @Id
    private String cashFlowId;
    private List<Processing> events;

    public record Processing(String type, String event) {

    }
}
