package com.multi.vidulum.portfolio_spec.app.commands.create;

import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio_spec.domain.DomainPortfolioSpecRepository;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
@AllArgsConstructor
public class CreatePortfolioSpecCommandHandler
        implements CommandHandler<CreatePortfolioSpecCommand, PortfolioSpec> {

    private final DomainPortfolioSpecRepository specRepository;
    private final DomainPortfolioRepository portfolioRepository;
    private final Clock clock;

    @Override
    public PortfolioSpec handle(CreatePortfolioSpecCommand command) {
        List<Asset> knownState = knownStateOf(command);

        PortfolioSpec spec = PortfolioSpec.from(
                PortfolioSpecId.generate(),
                command.userId(),
                command.connectionId(),
                knownState,
                command.snapshot(),
                ZonedDateTime.now(clock));

        PortfolioSpec saved = specRepository.save(spec);
        log.info("Specification [{}] for user [{}]: {} differences, {} needing an answer",
                saved.getId().getId(), command.userId().getId(),
                saved.getDifferences().size(), saved.openQuestions().size());
        return saved;
    }

    /**
     * Onboarding is not a special case — it is this same calculation with nothing known yet, so
     * an absent portfolio simply yields an empty list rather than a separate code path.
     */
    private List<Asset> knownStateOf(CreatePortfolioSpecCommand command) {
        if (command.portfolioId() == null) {
            return List.of();
        }
        return portfolioRepository.findById(command.portfolioId())
                .map(Portfolio::getAssets)
                .orElse(List.of());
    }
}
