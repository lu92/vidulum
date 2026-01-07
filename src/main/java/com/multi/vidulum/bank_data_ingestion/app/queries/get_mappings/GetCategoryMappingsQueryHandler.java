package com.multi.vidulum.bank_data_ingestion.app.queries.get_mappings;

import com.multi.vidulum.bank_data_ingestion.domain.CategoryMapping;
import com.multi.vidulum.bank_data_ingestion.domain.CategoryMappingRepository;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@AllArgsConstructor
public class GetCategoryMappingsQueryHandler
        implements QueryHandler<GetCategoryMappingsQuery, GetCategoryMappingsResult> {

    private final CategoryMappingRepository categoryMappingRepository;

    @Override
    public GetCategoryMappingsResult query(GetCategoryMappingsQuery query) {
        List<CategoryMapping> mappings = categoryMappingRepository.findByCashFlowId(query.cashFlowId());

        log.debug("Retrieved {} category mappings for CashFlow [{}]",
                mappings.size(), query.cashFlowId().id());

        return new GetCategoryMappingsResult(
                query.cashFlowId(),
                mappings.size(),
                mappings
        );
    }
}
