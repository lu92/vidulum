package com.multi.vidulum.portfolio.domain.portfolio;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioId {
    String id;

    public static PortfolioId of(String id) {
        return new PortfolioId(id);
    }

    public static PortfolioId generate() {
        return PortfolioId.of(UUID.randomUUID().toString());
    }
}
