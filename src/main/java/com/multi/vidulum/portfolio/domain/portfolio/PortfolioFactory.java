package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;

import java.util.LinkedList;

public class PortfolioFactory {

    public Portfolio createEmptyPortfolio(String name, UserId userId, Broker broker) {
        return Portfolio.builder()
                .userId(userId)
                .name(name)
                .broker(broker)
                .assets(new LinkedList<>())
                .investedBalance(Money.zero("USD"))
                .build();
    }
}
