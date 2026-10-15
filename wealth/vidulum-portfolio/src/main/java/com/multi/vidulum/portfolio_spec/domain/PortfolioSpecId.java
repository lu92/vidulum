package com.multi.vidulum.portfolio_spec.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSpecId {

    String id;

    public static PortfolioSpecId of(String id) {
        return new PortfolioSpecId(id);
    }

    public static PortfolioSpecId generate() {
        return PortfolioSpecId.of(UUID.randomUUID().toString());
    }
}
