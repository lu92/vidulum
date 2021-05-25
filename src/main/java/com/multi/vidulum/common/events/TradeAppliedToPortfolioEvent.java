package com.multi.vidulum.common.events;

import com.multi.vidulum.common.Event;
import com.multi.vidulum.common.StoredTrade;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeAppliedToPortfolioEvent implements Event<StoredTrade> {
    StoredTrade trade;

    @Override
    public StoredTrade getBody() {
        return trade;
    }
}
