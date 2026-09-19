package com.multi.vidulum.exchange_connection.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeAccountAlreadyConnectedException;
import com.multi.vidulum.exchange_connection.domain.ExchangeAdapter;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionNotFoundException;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.domain.ExchangeNotSupportedException;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates and resumes connections to exchanges.
 *
 * <p>Exchange-specific work is delegated to the {@link ExchangeAdapter} registered for the
 * broker; the adapters are collected from the context the same way {@code KafkaTopicConfig}
 * collects quotation providers, so adding an exchange means adding a module, not editing this
 * one.
 */
@Slf4j
@Service
public class ExchangeConnectionService {

    private final DomainExchangeConnectionRepository repository;
    private final Clock clock;
    private final Map<String, ExchangeAdapter> adapters;

    public ExchangeConnectionService(
            DomainExchangeConnectionRepository repository,
            Clock clock,
            List<ExchangeAdapter> registeredAdapters) {
        this.repository = repository;
        this.clock = clock;
        this.adapters = new LinkedHashMap<>();
        registeredAdapters.forEach(adapter -> adapters.put(key(adapter.broker()), adapter));
        log.info("Exchange adapters registered: {}", adapters.keySet());
    }

    /**
     * Registers an exchange account. The connection starts {@code PENDING} and has no portfolio
     * until it is confirmed.
     *
     * <p>An account that is already known is <b>not</b> quietly connected again — that would
     * produce a second portfolio over the same assets. Coming back after a break is
     * {@link #reconnect}, and the refusal says so.
     */
    public ExchangeConnection connect(UserId userId, ConnectExchangeCommand command) {
        Broker broker = Broker.of(command.broker().trim().toUpperCase(Locale.ROOT));
        ExchangeAdapter adapter = adapters.get(key(broker));
        if (adapter == null) {
            throw new ExchangeNotSupportedException(broker, adapters.keySet());
        }
        adapter.validateRegion(command.region());

        repository.findByAccount(userId, broker, command.environment(), command.accountUid())
                .ifPresent(existing -> {
                    throw new ExchangeAccountAlreadyConnectedException(
                            userId, broker, command.environment(), command.accountUid());
                });

        ExchangeConnection connection = ExchangeConnection.pending(
                ExchangeConnectionId.generate(),
                userId,
                broker,
                command.accountUid(),
                command.environment(),
                command.region(),
                ReportedKeyPermissions.of(command.reportedKeyPermissions()),
                command.credentialsMode() != null ? command.credentialsMode() : CredentialsMode.EXTERNAL,
                Currency.of(command.denominationCurrency()),
                now());

        ExchangeConnection saved = repository.save(connection);
        log.info("Connected [{}] account [{}] for user [{}] as [{}]",
                broker.getId(), command.accountUid(), userId.getId(), saved.getId().getId());
        return saved;
    }

    /**
     * Brings a revoked connection back. The portfolio from before the break is kept, so the
     * synchronisation that follows is computed against a non-empty known state.
     */
    public ExchangeConnection reconnect(UserId userId, ExchangeConnectionId id) {
        ExchangeConnection connection = ownedBy(userId, id);
        connection.reconnect(now());
        ExchangeConnection saved = repository.save(connection);
        log.info("Reconnected [{}] for user [{}]", id.getId(), userId.getId());
        return saved;
    }

    public ExchangeConnection get(UserId userId, ExchangeConnectionId id) {
        return ownedBy(userId, id);
    }

    public List<ExchangeConnection> list(UserId userId) {
        return repository.findByUserId(userId);
    }

    /** Names of the exchanges this instance can connect to. */
    public List<String> supportedExchanges() {
        return List.copyOf(adapters.keySet());
    }

    /**
     * Someone else's connection answers {@code not found}, not {@code forbidden}: a 403 would
     * confirm that the id exists.
     */
    private ExchangeConnection ownedBy(UserId userId, ExchangeConnectionId id) {
        return repository.findById(id)
                .filter(connection -> connection.getUserId().equals(userId))
                .orElseThrow(() -> new ExchangeConnectionNotFoundException(id));
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    private static String key(Broker broker) {
        return broker.getId().toUpperCase(Locale.ROOT);
    }
}
