package com.multi.vidulum.security.token;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;


@Builder
@Data
@Document("token")
public class Token {

    @Id
    public String id;

    public String token;

    public TokenType tokenType = TokenType.BEARER;

    public boolean revoked;

    public boolean expired;

    public String userId;
}
