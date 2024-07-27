package com.multi.vidulum.user.infrastructure;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.security.Role;
import com.multi.vidulum.user.domain.UserSnapshot;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Builder
@Getter
@ToString
@Document("user")
public class UserEntity {
    @Id
    private String id;
    private String username;
    private String password;
    private String email;
    private boolean isActive;
    private Role role;
    private List<String> portfolios;


    public static UserEntity fromSnapshot(UserSnapshot snapshot) {

        String id = Optional.ofNullable(snapshot.getUserId())
                .map(UserId::getId).orElse(null);

        List<String> portfolios = snapshot.getPortfolios().stream()
                .map(PortfolioId::getId)
                .collect(Collectors.toList());

        return UserEntity.builder()
                .id(id)
                .username(snapshot.getUsername())
                .password(snapshot.getPassword())
                .email(snapshot.getEmail())
                .role(snapshot.getRole())
                .isActive(snapshot.isActive())
                .portfolios(portfolios)
                .build();
    }

    public UserSnapshot toSnapshot() {
        List<PortfolioId> portfolioIds = portfolios.stream()
                .map(PortfolioId::of)
                .collect(Collectors.toList());
        return new UserSnapshot(UserId.of(id), username, password, email, role, isActive, portfolioIds);
    }
}
