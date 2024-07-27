package com.multi.vidulum.user.app.queries;

import com.multi.vidulum.shared.cqrs.queries.Query;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GetUserByEmailQuery implements Query {
    String email;
}
