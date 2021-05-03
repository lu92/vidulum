package com.multi.vidulum.user.app;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.user.app.commands.activate.ActivateUserCommand;
import com.multi.vidulum.user.app.commands.create.CreateUserCommand;
import com.multi.vidulum.user.app.queries.GetUserQuery;
import com.multi.vidulum.user.domain.User;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
public class UserRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    @PostMapping("/user")
    public UserDto.UserSummaryJson createUser(@RequestBody UserDto.CreateUserJson request) {
        CreateUserCommand command = CreateUserCommand.builder()
                .username(request.getUsername())
                .password(request.getPassword())
                .email(request.getEmail())
                .build();

        User user = commandGateway.send(command);

        return UserDto.UserSummaryJson.builder()
                .userId(user.getUserId().getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .isActive(user.isActive())
                .build();
    }

    @GetMapping("/user/{userId}")
    public UserDto.UserSummaryJson getUser(@PathVariable("userId") String userId) {
        GetUserQuery query = GetUserQuery.builder().userId(UserId.of(userId)).build();
        User user = queryGateway.send(query);
        return UserDto.UserSummaryJson.builder()
                .userId(user.getUserId().getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .isActive(user.isActive())
                .build();
    }

    @PutMapping("/user/{userId}")
    public void activateUser(@PathVariable("userId") String userId) {
        ActivateUserCommand command = ActivateUserCommand.builder().userId(UserId.of(userId)).build();
        commandGateway.send(command);
    }
}
