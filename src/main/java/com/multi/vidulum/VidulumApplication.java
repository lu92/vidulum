package com.multi.vidulum;

import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import com.multi.vidulum.user.infrastructure.UserEntity;
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
        mongoTemplate.dropCollection(UserEntity.class);
    }
}
