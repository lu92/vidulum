package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;

import java.util.LinkedList;

public class PortfolioFactory {

    public Portfolio createEmptyPortfolio(String name, UserId userId) {
        return Portfolio.builder()
                .userId(userId)
                .name(name)
                .assets(new LinkedList<>())
                .investedBalance(Money.zero("USD"))
                .build();
    }
}
