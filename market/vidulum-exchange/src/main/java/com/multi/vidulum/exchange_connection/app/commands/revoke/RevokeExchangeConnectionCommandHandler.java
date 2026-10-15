package com.multi.vidulum.exchange_connection.app.commands.revoke;

import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Slf4j
@Component
@AllArgsConstructor
public class RevokeExchangeConnectionCommandHandler
        implements CommandHandler<RevokeExchangeConnectionCommand, ExchangeConnection> {

    private final DomainExchangeConnectionRepository repository;
    private final Clock clock;

    @Override
    public ExchangeConnection handle(RevokeExchangeConnectionCommand command) {
        ExchangeConnection connection =
                repository.findOwnedOrThrow(command.userId(), command.connectionId());
        connection.revoke(command.reason(), ZonedDateTime.now(clock));
        ExchangeConnection saved = repository.save(connection);
        log.info("Connection [{}] revoked: {}", command.connectionId().getId(), command.reason());
        return saved;
    }
}
