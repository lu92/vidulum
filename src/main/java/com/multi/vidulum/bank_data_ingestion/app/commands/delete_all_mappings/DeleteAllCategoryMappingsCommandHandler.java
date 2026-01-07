package com.multi.vidulum.bank_data_ingestion.app.commands.delete_all_mappings;

import com.multi.vidulum.bank_data_ingestion.domain.CategoryMappingRepository;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class DeleteAllCategoryMappingsCommandHandler
        implements CommandHandler<DeleteAllCategoryMappingsCommand, DeleteAllCategoryMappingsResult> {

    private final CategoryMappingRepository categoryMappingRepository;

    @Override
    public DeleteAllCategoryMappingsResult handle(DeleteAllCategoryMappingsCommand command) {
        long deletedCount = categoryMappingRepository.deleteByCashFlowId(command.cashFlowId());

        log.info("Deleted {} category mappings for CashFlow [{}]",
                deletedCount, command.cashFlowId().id());

        return new DeleteAllCategoryMappingsResult(
                deletedCount > 0,
                deletedCount
        );
    }
}
