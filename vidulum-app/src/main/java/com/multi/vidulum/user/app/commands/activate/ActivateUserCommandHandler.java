package com.multi.vidulum.user.app.commands.activate;

import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.user.app.UserNotFoundException;
import com.multi.vidulum.user.domain.DomainUserRepository;
import com.multi.vidulum.user.domain.User;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ActivateUserCommandHandler implements CommandHandler<ActivateUserCommand, Void> {

    private final DomainUserRepository repository;

    @Override
    public Void handle(ActivateUserCommand command) {
        User user = repository.findById(command.getUserId())
                .orElseThrow(() -> new UserNotFoundException(command.getUserId()));

        user.activate();
        repository.save(user);
        log.info("User [{}] has been activated!",user.getUserId());
        return null;
    }
}
