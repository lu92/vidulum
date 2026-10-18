package com.multi.vidulum;

import com.multi.vidulum.common.auth.AuthenticatableUser;
import com.multi.vidulum.common.auth.RegisterUserCommand;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.user_financial_profile.app.UserFinancialProfileService;
import com.multi.vidulum.common.UserId;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Test-only command handler for user registration in cashflow module tests.
 * Stores users in {@link InMemoryAuthenticatableUserRepository} and creates
 * an empty financial profile (synchronously, since we don't have the Kafka
 * UserCreatedEventEmitter in this module).
 */
@Component
@AllArgsConstructor
public class TestRegisterUserCommandHandler implements CommandHandler<RegisterUserCommand, AuthenticatableUser> {

    private final InMemoryAuthenticatableUserRepository userRepository;
    private final UserFinancialProfileService userFinancialProfileService;

    @Override
    public AuthenticatableUser handle(RegisterUserCommand command) {
        InMemoryAuthenticatableUserRepository.TestUser user = userRepository.saveUser(
                command.getUsername(),
                command.getHashedPassword(),
                command.getEmail()
        );
        userFinancialProfileService.createEmptyProfile(UserId.of(user.userId()));
        return user;
    }
}
