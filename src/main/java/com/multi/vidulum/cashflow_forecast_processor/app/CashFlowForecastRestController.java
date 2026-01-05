package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@AllArgsConstructor
@RestController
@RequestMapping("/cash-flow-forecast")
public class CashFlowForecastRestController {

    private final CashFlowForecastStatementRepository statementRepository;
    private final CashFlowForecastMapper mapper;

    @GetMapping("/{cashFlowId}")
    public CashFlowForecastDto.CashFlowForecastStatementJson getForecastStatement(
            @PathVariable("cashFlowId") String cashFlowId) {
        CashFlowId id = new CashFlowId(cashFlowId);
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(id)
                .orElseThrow(() -> new CashFlowDoesNotExistsException(id));
        return mapper.map(statement);
    }
}
