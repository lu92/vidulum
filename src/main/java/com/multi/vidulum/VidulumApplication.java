package com.multi.vidulum;

import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import com.multi.vidulum.security.User;
import com.multi.vidulum.security.token.Token;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import com.multi.vidulum.trading.infrastructure.TradeEntity;
import com.multi.vidulum.user.infrastructure.UserEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.util.List;

@SpringBootApplication
public class VidulumApplication implements CommandLineRunner {

    @Autowired
    private MongoTemplate mongoTemplate;

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
        mongoTemplate.dropCollection(UserEntity.class);
        mongoTemplate.dropCollection(TradeEntity.class);
        mongoTemplate.dropCollection(User.class);
        mongoTemplate.dropCollection(Token.class);
    }
}
