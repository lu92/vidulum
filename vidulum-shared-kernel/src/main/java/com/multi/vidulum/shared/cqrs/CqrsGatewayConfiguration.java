package com.multi.vidulum.shared.cqrs;

import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two CQRS gateways, declared once for every context that needs them.
 *
 * <p><b>Why registration is separated from construction.</b> A gateway is built from every handler
 * in the context, and some handlers ask for a module contract whose implementation talks through a
 * gateway — {@code PlaceOrderCommandHandler} needs {@code PortfolioModuleApi}, which
 * {@code PortfolioModuleApiImpl} answers by sending commands. Collecting the handlers in the
 * gateway's own bean method therefore closed a cycle: gateway → handler → contract →
 * implementation → gateway. Spring reported it as {@code BeanCurrentlyInCreationException} at
 * startup, and the three implementations each dodged it with {@code @Lazy} on their gateways.
 *
 * <p>A gateway does not need its handlers while it is being constructed — only when something
 * calls {@code send}. So the bean methods below take no dependencies at all, and a
 * {@link SmartInitializingSingleton} fills both maps once every singleton exists. The cycle is
 * gone for good rather than deferred behind a proxy, which also means a context that cannot be
 * wired still fails at startup instead of on the first request.
 *
 * <p>Registration runs before Spring starts the {@code SmartLifecycle} beans, so Kafka listener
 * containers cannot deliver a message to an empty gateway.
 */
@Configuration
public class CqrsGatewayConfiguration {

    @Bean
    public CommandGateway commandGateway() {
        return new CommandGateway();
    }

    @Bean
    public QueryGateway queryGateway() {
        return new QueryGateway();
    }

    /**
     * Populates both gateways after the context has created its singletons. Depends on the
     * gateways, which depend on nothing — so nothing here can close a cycle.
     */
    @Bean
    public SmartInitializingSingleton cqrsHandlerRegistration(
            CommandGateway commandGateway,
            QueryGateway queryGateway,
            ObjectProvider<CommandHandler<?, ?>> commandHandlers,
            ObjectProvider<QueryHandler<?, ?>> queryHandlers) {
        return () -> {
            commandHandlers.orderedStream().forEach(commandGateway::registerCommandHandler);
            queryHandlers.orderedStream().forEach(queryGateway::registerQueryHandler);
        };
    }
}
