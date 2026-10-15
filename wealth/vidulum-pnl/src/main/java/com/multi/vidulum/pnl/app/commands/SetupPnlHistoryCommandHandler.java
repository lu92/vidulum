package com.multi.vidulum.pnl.app.commands;

import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedList;

@Slf4j
@Component
@AllArgsConstructor
public class SetupPnlHistoryCommandHandler implements CommandHandler<SetupPnlHistoryCommand, PnlHistory> {

    private final DomainPnlRepository pnlRepository;

    @Override
    public PnlHistory handle(SetupPnlHistoryCommand command) {
        PnlHistory cleanHistory = PnlHistory.builder()
                .userId(command.getUserId())
                .pnlStatements(new LinkedList<>())
                .build();
        PnlHistory persistedHistory = pnlRepository.save(cleanHistory);
        log.info("Pnl-history has been configured for new user [{}]", command.getUserId());
        return persistedHistory;
    }
}
