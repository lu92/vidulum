package com.multi.vidulum.shared.cqrs;

import com.multi.vidulum.shared.cqrs.commands.Command;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.shared.cqrs.queries.Query;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the arrangement that keeps module contracts out of a bean cycle.
 *
 * <p>{@link CycleShape} is the shape every gateway-based contract in this codebase has:
 *
 * <pre>
 *   LocationApiImpl  ──needs──▶  CommandGateway  ──would collect──▶  GreetHandler
 *         ▲                                                              │
 *         └──────────────────── needs LocationApi ◀──────────────────────┘
 * </pre>
 *
 * <p>That is {@code PortfolioModuleApiImpl} and {@code PlaceOrderCommandHandler} with the names
 * taken out. Assembling a gateway from {@code List<CommandHandler>} inside its own bean method
 * closes the loop, and the context refuses to start — which the last test here demonstrates, so
 * that {@link CqrsGatewayConfiguration} is known to be necessary and not merely harmless.
 *
 * <p>The guard is the context refresh itself: reintroduce eager collection and the first three
 * tests stop starting their context.
 */
class CqrsGatewayConfigurationTest {

    @Test
    void aContractImplementationMayInjectAGatewayEagerlyEvenThoughAHandlerNeedsTheContract() {
        try (AnnotationConfigApplicationContext context = contextOfCycleShape()) {
            CommandGateway commandGateway = context.getBean(CommandGateway.class);

            assertThat(commandGateway.<Greet, String>send(new Greet("Ada")))
                    .isEqualTo("Hello Ada, from PL");
        }
    }

    @Test
    void queryHandlersAreRegisteredTheSameWay() {
        try (AnnotationConfigApplicationContext context = contextOfCycleShape()) {
            QueryGateway queryGateway = context.getBean(QueryGateway.class);

            assertThat(queryGateway.<WhereAmI, String>send(new WhereAmI())).isEqualTo("PL");
        }
    }

    @Test
    void anUnregisteredCommandNamesItselfInsteadOfThrowingBlank() {
        try (AnnotationConfigApplicationContext context = contextOfCycleShape()) {
            CommandGateway commandGateway = context.getBean(CommandGateway.class);

            assertThatThrownBy(() -> commandGateway.send(new Unhandled()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(Unhandled.class.getName());
        }
    }

    /** The negative case: the same beans, the gateway assembled the old way, no context. */
    @Test
    void collectingHandlersWhileTheGatewayIsBuiltBringsTheCycleBack() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(
                EagerCollection.class, CycleShape.class))
                .isInstanceOf(BeanCreationException.class)
                .hasMessageContaining("Is there an unresolvable circular reference");
    }

    private static AnnotationConfigApplicationContext contextOfCycleShape() {
        return new AnnotationConfigApplicationContext(CqrsGatewayConfiguration.class, CycleShape.class);
    }

    public record Greet(String who) implements Command {
    }

    public record Unhandled() implements Command {
    }

    public record WhereAmI() implements Query {
    }

    /** What a module contract looks like from outside: one interface, no handlers named. */
    interface LocationApi {
        String where();
    }

    @Configuration
    static class CycleShape {

        /** The implementation, injecting both gateways eagerly — on purpose. */
        @Bean
        LocationApi locationApi(CommandGateway commandGateway, QueryGateway queryGateway) {
            return new LocationApiImpl(commandGateway, queryGateway);
        }

        /** A command handler that consumes the contract. This edge closes the cycle. */
        @Bean
        CommandHandler<Greet, String> greetHandler(LocationApi locationApi) {
            return new GreetHandler(locationApi);
        }

        @Bean
        QueryHandler<WhereAmI, String> whereAmIHandler() {
            return new WhereAmIHandler();
        }
    }

    /** How the gateways were assembled before {@link CqrsGatewayConfiguration}. */
    @Configuration
    static class EagerCollection {

        @Bean
        CommandGateway commandGateway(List<CommandHandler<?, ?>> commandHandlers) {
            CommandGateway commandGateway = new CommandGateway();
            commandHandlers.forEach(commandGateway::registerCommandHandler);
            return commandGateway;
        }

        @Bean
        QueryGateway queryGateway(List<QueryHandler<?, ?>> queryHandlers) {
            QueryGateway queryGateway = new QueryGateway();
            queryHandlers.forEach(queryGateway::registerQueryHandler);
            return queryGateway;
        }
    }

    /**
     * Answers its contract through the gateways, exactly as the three real implementations do. It
     * holds the command gateway even though {@code where()} only queries, because that is what
     * makes the cycle reachable from a command handler — and because the real implementations hold
     * both for the same reason: a contract has reads and writes.
     */
    static class LocationApiImpl implements LocationApi {

        private final CommandGateway commandGateway;
        private final QueryGateway queryGateway;

        LocationApiImpl(CommandGateway commandGateway, QueryGateway queryGateway) {
            this.commandGateway = commandGateway;
            this.queryGateway = queryGateway;
        }

        @Override
        public String where() {
            return queryGateway.send(new WhereAmI());
        }
    }

    /**
     * A named class rather than a lambda: the gateways find a handler's command type by reflecting
     * over its declared {@code handle} method, which a lambda does not expose.
     */
    static class GreetHandler implements CommandHandler<Greet, String> {

        private final LocationApi locationApi;

        GreetHandler(LocationApi locationApi) {
            this.locationApi = locationApi;
        }

        @Override
        public String handle(Greet command) {
            return "Hello %s, from %s".formatted(command.who(), locationApi.where());
        }
    }

    static class WhereAmIHandler implements QueryHandler<WhereAmI, String> {

        @Override
        public String query(WhereAmI query) {
            return "PL";
        }
    }
}
