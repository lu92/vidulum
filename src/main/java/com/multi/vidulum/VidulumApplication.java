package com.multi.vidulum;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.portfolio.domain.trades.BuyTrade;
import com.multi.vidulum.portfolio.domain.trades.SellTrade;
import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import java.time.Clock;
import java.util.List;

@SpringBootApplication
@EnableMongoRepositories(basePackages = "com.multi.vidulum")
public class VidulumApplication implements CommandLineRunner {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private PortfolioFactory portfolioFactory;

    @Autowired
    private DomainPortfolioRepository portfolioRepository;

    @Autowired
    private CommandGateway commandGateway;

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CommandGateway commandGateway(@Autowired List<CommandHandler<?, ?>> commandHandlers) {
        CommandGateway commandGateway = new CommandGateway();
        commandHandlers.forEach(commandGateway::registerCommandHandler);
        return commandGateway;
    }

    @Bean
    public QueryGateway queryGateway(@Autowired List<QueryHandler<?, ?>> queryHandlers) {
        QueryGateway queryGateway = new QueryGateway();
        queryHandlers.forEach(queryGateway::registerQueryHandler);
        return queryGateway;
    }

    public static void main(String[] args) {
        SpringApplication.run(VidulumApplication.class, args);
    }

    @Override
    public void run(String... args) {
        mongoTemplate.dropCollection(PortfolioEntity.class);
        UserId userId = UserId.of("Lucjan");
        Portfolio newPortfolio = portfolioFactory.createEmptyPortfolio("XYZ", userId);

        newPortfolio.depositMoney(Money.of(200, "USD"));

        AssetBasicInfo pslvBasicInfo = new AssetBasicInfo(
                Ticker.of("PSLV"),
                "Sprott Silver Trust",
                List.of("PM", "Silver")
        );

        newPortfolio.handleExecutedTrade(
                BuyTrade.builder()
                        .portfolioId(newPortfolio.getPortfolioId())
                        .tradeId(TradeId.of("XXX1"))
                        .ticker(Ticker.of("PSLV"))
                        .quantity(15)
                        .price(Money.of(11.50, "USD"))
                        .build(),
                pslvBasicInfo);

        newPortfolio.handleExecutedTrade(
                BuyTrade.builder()
                        .portfolioId(newPortfolio.getPortfolioId())
                        .tradeId(TradeId.of("XXX2"))
                        .ticker(Ticker.of("PSLV"))
                        .quantity(10)
                        .price(Money.of(9.50, "USD"))
                        .build(),
                pslvBasicInfo);

        newPortfolio.handleExecutedTrade(
                SellTrade.builder()
                        .portfolioId(newPortfolio.getPortfolioId())
                        .tradeId(TradeId.of("XXX3"))
                        .ticker(Ticker.of("PSLV"))
                        .quantity(10)
                        .price(Money.of(9.50, "USD"))
                        .build()
        );


        Portfolio savedPortfolio = portfolioRepository.save(newPortfolio);
        System.out.println(savedPortfolio);

    }
}
