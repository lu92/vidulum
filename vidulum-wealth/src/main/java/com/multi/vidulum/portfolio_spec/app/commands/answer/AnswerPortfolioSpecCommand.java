package com.multi.vidulum.portfolio_spec.app.commands.answer;

import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.Answer;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.util.List;

public record AnswerPortfolioSpecCommand(
        UserId userId,
        PortfolioSpecId specId,
        List<GivenAnswer> answers) implements Command {

    /** Anchored to the batch: ticker, position and the quantity the question was asked about. */
    public record GivenAnswer(Ticker ticker, SubName subName, Quantity quantity, Answer answer) {
    }
}
