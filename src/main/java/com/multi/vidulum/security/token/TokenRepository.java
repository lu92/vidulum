package com.multi.vidulum.security.token;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TokenRepository extends MongoRepository<Token, Integer> {
    List<Token> findByUserId(String userId);

    Optional<Token> findByToken(String token);
}
