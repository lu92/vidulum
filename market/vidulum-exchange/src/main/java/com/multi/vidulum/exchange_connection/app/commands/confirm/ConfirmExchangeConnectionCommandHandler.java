package com.multi.vidulum.exchange_connection.app.commands.confirm;

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
public class ConfirmExchangeConnectionCommandHandler
        implements CommandHandler<ConfirmExchangeConnectionCommand, ExchangeConnection> {

    private final DomainExchangeConnectionRepository repository;
    private final Clock clock;

    @Override
    public ExchangeConnection handle(ConfirmExchangeConnectionCommand command) {
        ExchangeConnection connection =
                repository.findOwnedOrThrow(command.userId(), command.connectionId());
        connection.confirm(command.portfolioId(), ZonedDateTime.now(clock));
        ExchangeConnection saved = repository.save(connection);
        log.info("Connection [{}] now serving portfolio [{}]",
                command.connectionId().getId(), command.portfolioId().getId());
        return saved;
    }
}
