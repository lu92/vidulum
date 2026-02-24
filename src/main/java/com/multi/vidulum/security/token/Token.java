package com.multi.vidulum.security.token;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;


@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("token")
public class Token {

    @Id
    public String id;

    @Indexed(unique = true)
    public String token;

    @Builder.Default
    public TokenType tokenType = TokenType.BEARER;

    public boolean revoked;

    public boolean expired;

    public String userId;

    public Instant createdAt;
}
