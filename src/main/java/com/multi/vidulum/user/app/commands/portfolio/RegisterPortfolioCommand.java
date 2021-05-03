package com.multi.vidulum.user.app.commands.portfolio;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Value;

@Value
public class RegisterPortfolioCommand implements Command {
    UserId userId;
    Broker broker;
}
