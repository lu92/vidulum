package com.multi.vidulum.user.app.queries;

import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import com.multi.vidulum.user.app.UserNotFoundException;
import com.multi.vidulum.user.domain.DomainUserRepository;
import com.multi.vidulum.user.domain.User;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetUserByUsernameQueryHandler implements QueryHandler<GetUserByUsernameQuery, User> {

    private final DomainUserRepository repository;

    @Override
    public User query(GetUserByUsernameQuery query) {
        return repository.findByUsername(query.getUsername())
                .orElseThrow(() -> new UserNotFoundException(query.getUsername()));
    }
}
