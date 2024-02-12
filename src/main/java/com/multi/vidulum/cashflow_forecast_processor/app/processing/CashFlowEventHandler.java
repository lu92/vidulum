package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.JsonContent;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

public interface CashFlowEventHandler <T extends CashFlowEvent> {
    void handle(T cashFlowEvent);

    default Checksum getChecksum(CashFlowEvent event) {
        String jsonizedEvent = JsonContent.asJson(event).content();
        return new Checksum(DigestUtils.md5DigestAsHex(jsonizedEvent.getBytes(StandardCharsets.UTF_8)));
    }
}
