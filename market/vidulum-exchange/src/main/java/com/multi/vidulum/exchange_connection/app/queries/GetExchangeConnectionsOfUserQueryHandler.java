package com.multi.vidulum.exchange_connection.app.queries;

import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeAdapters;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class GetExchangeConnectionsOfUserQueryHandler
        implements QueryHandler<GetExchangeConnectionsOfUserQuery, GetExchangeConnectionsOfUserQueryHandler.Result> {

    private final DomainExchangeConnectionRepository repository;
    private final ExchangeAdapters adapters;

    @Override
    public Result query(GetExchangeConnectionsOfUserQuery query) {
        return new Result(repository.findByUserId(query.userId()), adapters.supportedExchanges());
    }

    /**
     * The supported exchanges ride along because a "connect an exchange" screen needs them and
     * would otherwise hardcode the list. Different question from
     * {@code GET /exchange/{name}/status}, which asks whether an exchange is reachable right now.
     */
    public record Result(List<ExchangeConnection> connections, List<String> supportedExchanges) {
    }
}
