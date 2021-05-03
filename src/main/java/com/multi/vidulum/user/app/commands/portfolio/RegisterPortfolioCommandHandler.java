package com.multi.vidulum.user.app.commands.portfolio;

import com.multi.vidulum.shared.cqrs.commands.CommandHandler;

public class RegisterPortfolioCommandHandler implements CommandHandler<RegisterPortfolioCommand, Void> {

    @Override
    public Void handle(RegisterPortfolioCommand command) {
        return null;
    }
}
