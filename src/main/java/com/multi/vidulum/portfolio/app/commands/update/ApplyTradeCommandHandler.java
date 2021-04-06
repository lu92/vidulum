package com.multi.vidulum.portfolio.app.commands.update;

import com.mongodb.client.result.UpdateResult;
import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.trades.BuyTrade;
import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class ApplyTradeCommandHandler implements CommandHandler<ApplyTradeCommand, Void> {

    private final DomainPortfolioRepository repository;
    MongoTemplate mongoTemplate;

    @Override
    public Void handle(ApplyTradeCommand command) {

        Portfolio portfolio = repository
                .findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));


        BuyTrade trade = BuyTrade.builder()
                .tradeId(command.getTradeId())
                .ticker(command.getTicker())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .build();


        Query query = new Query().addCriteria(Criteria.where("id").is(command.getPortfolioId().getId()));
        Update update = new Update();
//        update.fi
        UpdateResult updateResult = mongoTemplate.updateFirst(query, update, PortfolioEntity.class);

        return null;
    }
}
