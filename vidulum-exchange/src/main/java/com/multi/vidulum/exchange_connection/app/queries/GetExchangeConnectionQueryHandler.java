package com.multi.vidulum.exchange_connection.app.queries;

import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetExchangeConnectionQueryHandler
        implements QueryHandler<GetExchangeConnectionQuery, ExchangeConnection> {

    private final DomainExchangeConnectionRepository repository;

    @Override
    public ExchangeConnection query(GetExchangeConnectionQuery query) {
        return repository.findOwnedOrThrow(query.userId(), query.connectionId());
    }
}
