package com.multi.vidulum.security.token;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TokenRepository extends MongoRepository<Token, String> {

    List<Token> findByUserId(String userId);

    Optional<Token> findByToken(String token);

    List<Token> findByUserIdAndTokenType(String userId, TokenType tokenType);

    @Query("{ 'userId': ?0, 'revoked': false, 'expired': false }")
    List<Token> findAllValidTokensByUserId(String userId);

    @Query("{ 'userId': ?0, 'tokenType': ?1, 'revoked': false, 'expired': false }")
    List<Token> findAllValidTokensByUserIdAndType(String userId, TokenType tokenType);
}
