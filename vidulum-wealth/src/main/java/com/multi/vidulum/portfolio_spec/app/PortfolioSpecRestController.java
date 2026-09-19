package com.multi.vidulum.portfolio_spec.app;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.portfolio_spec.app.commands.answer.AnswerPortfolioSpecCommand;
import com.multi.vidulum.portfolio_spec.app.commands.confirm.ConfirmPortfolioSpecCommand;
import com.multi.vidulum.portfolio_spec.app.commands.create.CreatePortfolioSpecCommand;
import com.multi.vidulum.portfolio_spec.app.queries.GetPortfolioSpecQuery;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * Specifications belong to the authenticated user; the id is never taken from the request.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/portfolio-spec")
public class PortfolioSpecRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final Clock clock;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortfolioSpecDto.PortfolioSpecJson create(
            @Valid @RequestBody PortfolioSpecDto.CreateSpecJson request) {

        PortfolioSpec spec = commandGateway.send(new CreatePortfolioSpecCommand(
                currentUser(),
                request.connectionId(),
                request.portfolioId() != null ? PortfolioId.of(request.portfolioId()) : null,
                request.toSnapshot()));

        return PortfolioSpecDto.PortfolioSpecJson.from(spec, now());
    }

    /** What still has to be decided. Answers {@code 404} for someone else's specification. */
    @GetMapping("/{specId}")
    public PortfolioSpecDto.PortfolioSpecJson get(@PathVariable String specId) {
        PortfolioSpec spec = queryGateway.send(
                new GetPortfolioSpecQuery(currentUser(), PortfolioSpecId.of(specId)));
        return PortfolioSpecDto.PortfolioSpecJson.from(spec, now());
    }

    /**
     * Records what the user decided. Answering everything leaves the specification
     * {@code CONFIRMED} and ready to apply.
     */
    @PutMapping("/{specId}/answers")
    public PortfolioSpecDto.PortfolioSpecJson answer(
            @PathVariable String specId,
            @Valid @RequestBody PortfolioSpecDto.AnswerSpecJson request) {

        PortfolioSpec spec = commandGateway.send(new AnswerPortfolioSpecCommand(
                currentUser(),
                PortfolioSpecId.of(specId),
                request.answers().stream()
                        .map(PortfolioSpecDto.GivenAnswerJson::toDomain)
                        .toList()));

        return PortfolioSpecDto.PortfolioSpecJson.from(spec, now());
    }

    /**
     * Applies the specification: creates the portfolio and puts the connection into service.
     *
     * <p>Answers {@code 409} when the exchange has moved on since the specification was built —
     * it is recomputed and has to be reviewed again rather than applied to a world that no
     * longer exists.
     */
    @PostMapping("/{specId}/confirm")
    public PortfolioSpecDto.PortfolioSpecJson confirm(
            @PathVariable String specId,
            @Valid @RequestBody PortfolioSpecDto.ConfirmSpecJson request) {

        PortfolioSpec spec = commandGateway.send(new ConfirmPortfolioSpecCommand(
                currentUser(),
                PortfolioSpecId.of(specId),
                request.portfolioName(),
                Currency.of(request.denominationCurrency()),
                request.toSnapshot()));

        return PortfolioSpecDto.PortfolioSpecJson.from(spec, now());
    }

    private UserId currentUser() {
        return authenticatedUserProvider.getCurrentUserId();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
