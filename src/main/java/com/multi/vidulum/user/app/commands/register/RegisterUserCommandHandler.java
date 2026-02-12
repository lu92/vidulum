package com.multi.vidulum.user.app.commands.register;

import com.multi.vidulum.common.BusinessIdGenerator;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.events.UserCreatedEvent;
import com.multi.vidulum.security.Role;
import com.multi.vidulum.shared.UserCreatedEventEmitter;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.user.domain.DomainUserRepository;
import com.multi.vidulum.user.domain.User;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedList;

@Slf4j
@Component
@AllArgsConstructor
public class RegisterUserCommandHandler implements CommandHandler<RegisterUserCommand, User> {

    private final DomainUserRepository domainUserRepository;
    private final UserCreatedEventEmitter userCreatedEventEmitter;
    private final BusinessIdGenerator businessIdGenerator;

    @Override
    public User handle(RegisterUserCommand command) {
        UserId userId = businessIdGenerator.generateUserId();

        User newUser = User.builder()
                .userId(userId)
                .username(command.getUsername())
                .password(command.getHashedPassword())
                .email(command.getEmail())
                .role(Role.USER)
                .isActive(true)
                .portfolios(new LinkedList<>())
                .build();

        User savedUser = domainUserRepository.save(newUser);
        log.info("New User [{}] has been registered!", savedUser.getUserId());

        userCreatedEventEmitter.emit(
                UserCreatedEvent.builder()
                        .userId(savedUser.getUserId())
                        .build()
        );

        return savedUser;
    }
}
