package com.multi.vidulum.user.app.queries;

import com.multi.vidulum.shared.cqrs.queries.Query;
import lombok.Value;

@Value(staticConstructor = "of")
public class GetUserByUsernameQuery implements Query {
    String username;
}
