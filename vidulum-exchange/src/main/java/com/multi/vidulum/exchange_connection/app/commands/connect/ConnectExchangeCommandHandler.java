package com.multi.vidulum.exchange_connection.app.commands.connect;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeAccountAlreadyConnectedException;
import com.multi.vidulum.exchange_connection.domain.ExchangeAdapters;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeAdapter;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Locale;

@Slf4j
@Component
@AllArgsConstructor
public class ConnectExchangeCommandHandler
        implements CommandHandler<ConnectExchangeCommand, ExchangeConnection> {

    private final DomainExchangeConnectionRepository repository;
    private final ExchangeAdapters adapters;
    private final Clock clock;

    @Override
    public ExchangeConnection handle(ConnectExchangeCommand command) {
        Broker broker = normalised(command.broker());
        ExchangeAdapter adapter = adapters.require(broker);
        adapter.validateRegion(command.region());

        // An account that is already known is NOT quietly connected again — that would produce a
        // second portfolio over the same assets. Coming back after a break is ReconnectExchangeCommand.
        repository.findByAccount(command.userId(), broker, command.environment(), command.accountUid())
                .ifPresent(existing -> {
                    throw new ExchangeAccountAlreadyConnectedException(
                            command.userId(), broker, command.environment(), command.accountUid());
                });

        ExchangeConnection connection = ExchangeConnection.pending(
                ExchangeConnectionId.generate(),
                command.userId(),
                broker,
                command.accountUid(),
                command.environment(),
                command.region(),
                ReportedKeyPermissions.of(command.reportedKeyPermissions()),
                command.credentialsMode() != null ? command.credentialsMode() : CredentialsMode.EXTERNAL,
                command.denominationCurrency(),
                ZonedDateTime.now(clock));

        ExchangeConnection saved = repository.save(connection);
        log.info("Connected [{}] account [{}] for user [{}] as [{}]",
                broker.getId(), command.accountUid(), command.userId().getId(), saved.getId().getId());
        return saved;
    }

    /** The broker id is part of the natural key, so letter case must not split one account in two. */
    private static Broker normalised(Broker broker) {
        return Broker.of(broker.getId().trim().toUpperCase(Locale.ROOT));
    }
}
