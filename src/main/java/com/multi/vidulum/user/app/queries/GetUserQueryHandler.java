package com.multi.vidulum.user.app.queries;

import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import com.multi.vidulum.user.app.UserNotFoundException;
import com.multi.vidulum.user.domain.DomainUserRepository;
import com.multi.vidulum.user.domain.User;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetUserQueryHandler implements QueryHandler<GetUserQuery, User> {

    private final DomainUserRepository repository;

    @Override
    public User query(GetUserQuery query) {
        return repository.findById(query.getUserId())
                .orElseThrow(() -> new UserNotFoundException(query.getUserId()));
    }
}
