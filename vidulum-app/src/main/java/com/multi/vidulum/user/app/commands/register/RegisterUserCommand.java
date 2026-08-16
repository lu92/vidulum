package com.multi.vidulum.user.app.commands.register;

import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RegisterUserCommand implements Command {
    String username;
    String hashedPassword;
    String email;
}
