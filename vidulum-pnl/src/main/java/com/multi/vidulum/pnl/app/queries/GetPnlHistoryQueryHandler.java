package com.multi.vidulum.pnl.app.queries;

import com.multi.vidulum.pnl.app.PnlHistoryNotFoundException;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetPnlHistoryQueryHandler implements QueryHandler<GetPnlHistoryQuery, PnlHistory> {

    private final DomainPnlRepository pnlRepository;

    @Override
    public PnlHistory query(GetPnlHistoryQuery query) {
        return pnlRepository.findByUser(query.getUserId())
                .orElseThrow(() -> new PnlHistoryNotFoundException(query.getUserId()));
    }
}
