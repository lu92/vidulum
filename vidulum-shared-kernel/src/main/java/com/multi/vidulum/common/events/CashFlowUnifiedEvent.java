package com.multi.vidulum.common.events;

import com.multi.vidulum.shared.ddd.event.IntegrationEvent;
import com.multi.vidulum.common.JsonContent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashFlowUnifiedEvent implements IntegrationEvent {
    Map<String, Object> metadata;
    JsonContent content;
}
