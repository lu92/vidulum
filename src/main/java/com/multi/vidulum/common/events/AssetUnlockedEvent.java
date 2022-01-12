package com.multi.vidulum.common.events;

import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetUnlockedEvent {
    PortfolioId portfolioId;
    Ticker ticker;
    Quantity quantity;
}
