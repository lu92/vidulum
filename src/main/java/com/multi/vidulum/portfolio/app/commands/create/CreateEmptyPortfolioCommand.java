package com.multi.vidulum.portfolio.app.commands.create;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Value;

@Value
public class CreateEmptyPortfolioCommand implements Command {
    String name;
    UserId userId;
}
