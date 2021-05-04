package com.multi.vidulum.user.app.commands.portfolio.register;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RegisterPortfolioCommand implements Command {
    String name;
    UserId userId;
    Broker broker;
}
