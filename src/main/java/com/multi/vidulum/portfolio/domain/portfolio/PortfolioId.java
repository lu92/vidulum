package com.multi.vidulum.portfolio.domain.portfolio;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioId {
    String id;

    public static PortfolioId of(String id) {
        return new PortfolioId(id);
    }
}
