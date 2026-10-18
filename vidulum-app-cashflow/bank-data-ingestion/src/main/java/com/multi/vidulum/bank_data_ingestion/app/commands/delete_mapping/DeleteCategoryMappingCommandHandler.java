package com.multi.vidulum.bank_data_ingestion.app.commands.delete_mapping;

import com.multi.vidulum.bank_data_ingestion.domain.CategoryMapping;
import com.multi.vidulum.bank_data_ingestion.domain.CategoryMappingNotFoundException;
import com.multi.vidulum.bank_data_ingestion.domain.CategoryMappingRepository;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class DeleteCategoryMappingCommandHandler
        implements CommandHandler<DeleteCategoryMappingCommand, DeleteCategoryMappingResult> {

    private final CategoryMappingRepository categoryMappingRepository;

    @Override
    public DeleteCategoryMappingResult handle(DeleteCategoryMappingCommand command) {
        CategoryMapping mapping = categoryMappingRepository.findById(command.mappingId())
                .orElseThrow(() -> new CategoryMappingNotFoundException(command.mappingId()));

        categoryMappingRepository.deleteById(command.mappingId());

        log.info("Deleted category mapping [{}] for bank category [{}] in CashFlow [{}]",
                command.mappingId().id(), mapping.bankCategoryName(), command.cashFlowId().id());

        return new DeleteCategoryMappingResult(
                true,
                command.mappingId(),
                mapping.bankCategoryName()
        );
    }
}
