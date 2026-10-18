package com.multi.vidulum.bank_data_ingestion.app.commands.delete_staging_session;

import com.multi.vidulum.bank_data_ingestion.domain.StagedTransactionRepository;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class DeleteStagingSessionCommandHandler
        implements CommandHandler<DeleteStagingSessionCommand, DeleteStagingSessionResult> {

    private final StagedTransactionRepository stagedTransactionRepository;

    @Override
    public DeleteStagingSessionResult handle(DeleteStagingSessionCommand command) {
        long deletedCount = stagedTransactionRepository.deleteByStagingSessionId(command.stagingSessionId());

        if (deletedCount > 0) {
            log.info("Deleted staging session [{}] with {} transactions for CashFlow [{}]",
                    command.stagingSessionId().id(),
                    deletedCount,
                    command.cashFlowId().id());
        } else {
            log.warn("No staged transactions found for session [{}] - possibly already expired or deleted",
                    command.stagingSessionId().id());
        }

        return new DeleteStagingSessionResult(
                command.cashFlowId(),
                command.stagingSessionId(),
                deletedCount > 0,
                deletedCount
        );
    }
}
