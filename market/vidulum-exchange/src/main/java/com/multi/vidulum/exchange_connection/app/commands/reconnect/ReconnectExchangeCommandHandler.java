package com.multi.vidulum.exchange_connection.app.commands.reconnect;

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
public class ReconnectExchangeCommandHandler
        implements CommandHandler<ReconnectExchangeCommand, ExchangeConnection> {

    private final DomainExchangeConnectionRepository repository;
    private final Clock clock;

    @Override
    public ExchangeConnection handle(ReconnectExchangeCommand command) {
        ExchangeConnection connection =
                repository.findOwnedOrThrow(command.userId(), command.connectionId());
        connection.reconnect(ZonedDateTime.now(clock));
        ExchangeConnection saved = repository.save(connection);
        log.info("Reconnected [{}] for user [{}]",
                command.connectionId().getId(), command.userId().getId());
        return saved;
    }
}
