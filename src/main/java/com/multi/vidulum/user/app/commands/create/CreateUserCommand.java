package com.multi.vidulum.user.app.commands.create;

import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CreateUserCommand implements Command {
    String username;
    String password;
    String email;
}
