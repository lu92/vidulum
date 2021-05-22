package com.multi.vidulum.common.events;

import com.multi.vidulum.common.StoredTrade;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeStoredEvent {
    StoredTrade trade;
}
