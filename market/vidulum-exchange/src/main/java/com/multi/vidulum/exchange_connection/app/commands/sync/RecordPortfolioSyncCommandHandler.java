package com.multi.vidulum.exchange_connection.app.commands.sync;

import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class RecordPortfolioSyncCommandHandler
        implements CommandHandler<RecordPortfolioSyncCommand, ExchangeConnection> {

    private final DomainExchangeConnectionRepository repository;

    @Override
    public ExchangeConnection handle(RecordPortfolioSyncCommand command) {
        ExchangeConnection connection =
                repository.findOwnedOrThrow(command.userId(), command.connectionId());
        connection.recordSync(command.syncedAt());
        ExchangeConnection saved = repository.save(connection);
        log.info("Portfolio of connection [{}] is in agreement with the exchange as of [{}]",
                command.connectionId().getId(), command.syncedAt());
        return saved;
    }
}
