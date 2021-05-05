package com.multi.vidulum.user.app.commands.activate;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ActivateUserCommand implements Command {
    UserId userId;
}
