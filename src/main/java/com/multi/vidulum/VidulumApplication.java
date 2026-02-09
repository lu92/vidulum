package com.multi.vidulum;

import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.CategoryMappingEntity;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.ImportJobEntity;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagedTransactionEntity;
import com.multi.vidulum.cashflow.infrastructure.entity.CashFlowEntity;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity.CashFlowForecastStatementEntity;
import com.multi.vidulum.pnl.infrastructure.entities.PnlHistoryEntity;
import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import com.multi.vidulum.security.auth.AuthenticationService;
import com.multi.vidulum.security.auth.RegisterRequest;
import com.multi.vidulum.security.token.Token;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import com.multi.vidulum.task.infrastructure.TaskEntity;
import com.multi.vidulum.trading.infrastructure.OrderEntity;
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

import static com.multi.vidulum.security.Role.ADMIN;
import static com.multi.vidulum.security.Role.MANAGER;

@SpringBootApplication
public class VidulumApplication {

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

    @Bean
    public CommandLineRunner commandLineRunner(
            AuthenticationService service
    ) {
        clearData();
        return args -> {
            var admin = RegisterRequest.builder()
                    .username("Admin")
                    .email("admin@mail.com")
                    .password("password")
                    .role(ADMIN)
                    .build();
            System.out.println("Admin token: " + service.register(admin).getAccessToken());

            var manager = RegisterRequest.builder()
                    .username("Manager")
                    .email("manager@mail.com")
                    .password("password")
                    .role(MANAGER)
                    .build();
            System.out.println("Manager token: " + service.register(manager).getAccessToken());
        };
    }

    private void clearData() {
        // Security & User
        mongoTemplate.dropCollection(Token.class);
        mongoTemplate.dropCollection(UserEntity.class);

        // Portfolio & Trading
        mongoTemplate.dropCollection(PortfolioEntity.class);
        mongoTemplate.dropCollection(TradeEntity.class);
        mongoTemplate.dropCollection(OrderEntity.class);

        // CashFlow
        mongoTemplate.dropCollection(CashFlowEntity.class);
        mongoTemplate.dropCollection(CashFlowForecastEntity.class);
        mongoTemplate.dropCollection(CashFlowForecastStatementEntity.class);

        // Bank Data Ingestion
        mongoTemplate.dropCollection(StagedTransactionEntity.class);
        mongoTemplate.dropCollection(CategoryMappingEntity.class);
        mongoTemplate.dropCollection(ImportJobEntity.class);

        // Other
        mongoTemplate.dropCollection(TaskEntity.class);
        mongoTemplate.dropCollection(PnlHistoryEntity.class);
    }
}
