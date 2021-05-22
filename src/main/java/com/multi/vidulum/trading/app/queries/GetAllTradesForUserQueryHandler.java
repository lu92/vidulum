package com.multi.vidulum.trading.app.queries;

import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetAllTradesForUserQueryHandler implements QueryHandler<GetAllTradesForUserQuery, Void> {
    @Override
    public Void query(GetAllTradesForUserQuery query) {
        return null;
    }
}
