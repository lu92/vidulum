package com.multi.vidulum.common.auth;

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
