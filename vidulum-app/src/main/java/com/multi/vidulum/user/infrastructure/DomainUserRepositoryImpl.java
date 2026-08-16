package com.multi.vidulum.user.infrastructure;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.user.domain.DomainUserRepository;
import com.multi.vidulum.user.domain.User;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@AllArgsConstructor
public class DomainUserRepositoryImpl implements DomainUserRepository {

    private final UserMongoRepository userMongoRepository;

    @Override
    public Optional<User> findById(UserId userId) {
        return userMongoRepository.findById(userId.getId())
                .map(UserEntity::toSnapshot)
                .map(User::from);
    }

    @Override
    public User save(User aggregate) {
        return User.from(
                userMongoRepository.save(UserEntity.fromSnapshot(aggregate.getSnapshot()))
                        .toSnapshot()
        );
    }

    @Override
    public boolean existsByUsername(String username) {
        return userMongoRepository.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userMongoRepository.existsByEmail(email);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return userMongoRepository.findByUsername(username)
                .map(UserEntity::toSnapshot)
                .map(User::from);
    }
}
