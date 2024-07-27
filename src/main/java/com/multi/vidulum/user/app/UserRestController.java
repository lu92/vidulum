package com.multi.vidulum.user.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.security.config.JwtService;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.user.app.commands.activate.ActivateUserCommand;
import com.multi.vidulum.user.app.commands.create.CreateUserCommand;
import com.multi.vidulum.user.app.commands.portfolio.register.RegisterPortfolioCommand;
import com.multi.vidulum.user.app.queries.GetUserByEmailQuery;
import com.multi.vidulum.user.app.queries.GetUserQuery;
import com.multi.vidulum.user.domain.User;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

@RestController
@AllArgsConstructor
public class UserRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final JwtService jwtService;

    @PostMapping("/user")
    public UserDto.UserSummaryJson createUser(@RequestBody UserDto.CreateUserJson request) {
        CreateUserCommand command = CreateUserCommand.builder()
                .username(request.getUsername())
                .password(request.getPassword())
                .email(request.getEmail())
                .build();

        User user = commandGateway.send(command);

        return mapUserToSummary(user);
    }

    @GetMapping("/user/{userId}")
    public UserDto.UserSummaryJson getUser(@PathVariable("userId") String userId) {
        GetUserQuery query = GetUserQuery.builder().userId(UserId.of(userId)).build();
        User user = queryGateway.send(query);
        return mapUserToSummary(user);
    }

    @GetMapping("/user")
    public UserDto.UserSummaryJson getUserByJwtToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing authorization header");
        }
        String jwt = authHeader.substring(7);
        String email = jwtService.extractEmail(jwt);

        GetUserByEmailQuery query = GetUserByEmailQuery.builder().email(email).build();
        User user = queryGateway.send(query);
        return mapUserToSummary(user);
    }

    @PutMapping("/user/{userId}")
    public void activateUser(@PathVariable("userId") String userId) {
        ActivateUserCommand command = ActivateUserCommand.builder().userId(UserId.of(userId)).build();
        commandGateway.send(command);
    }

    @PostMapping("/user/portfolio/register")
    public UserDto.PortfolioRegistrationSummaryJson registerPortfolio(@RequestBody UserDto.RegisterPortfolioJson request) {
        RegisterPortfolioCommand command = RegisterPortfolioCommand.builder()
                .userId(UserId.of(request.getUserId()))
                .name(request.getName())
                .broker(Broker.of(request.getBroker()))
                .currency(Currency.of(request.getAllowedDepositCurrency()))
                .build();

        PortfolioId portfolioId = commandGateway.send(command);
        return UserDto.PortfolioRegistrationSummaryJson.builder()
                .userId(request.getUserId())
                .name(request.getName())
                .broker(request.getBroker())
                .portfolioId(portfolioId.getId())
                .allowedDepositCurrency(request.getAllowedDepositCurrency())
                .build();
    }

    private UserDto.UserSummaryJson mapUserToSummary(User user) {
        return UserDto.UserSummaryJson.builder()
                .userId(user.getUserId().getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .isActive(user.isActive())
                .portolioIds(user.getPortfolios().stream().map(PortfolioId::getId).collect(Collectors.toList()))
                .build();
    }
}
