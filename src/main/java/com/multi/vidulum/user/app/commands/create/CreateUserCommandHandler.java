package com.multi.vidulum.user.app.commands.create;

import com.multi.vidulum.common.BusinessIdGenerator;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.events.UserCreatedEvent;
import com.multi.vidulum.shared.UserCreatedEventEmitter;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.user.domain.DomainUserRepository;
import com.multi.vidulum.user.domain.User;
import com.multi.vidulum.user.domain.UserFactory;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class CreateUserCommandHandler implements CommandHandler<CreateUserCommand, User> {

    private final UserFactory userFactory;
    private final DomainUserRepository domainUserRepository;
    private final UserCreatedEventEmitter userCreatedEventEmitter;
    private final BusinessIdGenerator businessIdGenerator;

    @Override
    public User handle(CreateUserCommand command) {
        User newUser = createNewUser(command);
        User savedUser = domainUserRepository.save(newUser);
        log.info("New User [{}] has been created!",  savedUser.getUserId());
        userCreatedEventEmitter.emit(
                UserCreatedEvent.builder()
                        .userId(savedUser.getUserId())
                        .build()
        );
        return savedUser;
    }

    private User createNewUser(CreateUserCommand command) {
        UserId userId = businessIdGenerator.generateUserId();
        return userFactory.createUser(
                userId,
                command.getUsername(),
                command.getPassword(),
                command.getEmail()
        );
    }
}
