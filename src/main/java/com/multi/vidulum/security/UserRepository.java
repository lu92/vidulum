package com.multi.vidulum.security;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, Integer> {

  Optional<User> findByEmail(String email);
  boolean existsByEmail(String email);

}
