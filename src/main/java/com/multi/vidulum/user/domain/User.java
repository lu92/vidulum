package com.multi.vidulum.user.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.security.Role;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class User implements Aggregate<UserId, UserSnapshot> {

    private UserId userId;
    private String username;
    private String password;
    private String email;
    private Role role;
    private boolean isActive;
    private List<PortfolioId> portfolios;

    @Override
    public UserSnapshot getSnapshot() {
        return new UserSnapshot(userId, username, password, email, role, isActive, portfolios);
    }

    public static User from(UserSnapshot snapshot) {
        return User.builder()
                .userId(snapshot.getUserId())
                .username(snapshot.getUsername())
                .password(snapshot.getPassword())
                .email(snapshot.getEmail())
                .role(snapshot.getRole())
                .isActive(snapshot.isActive())
                .portfolios(snapshot.getPortfolios())
                .build();
    }

    public void activate() {
        isActive = true;
    }

    public void deactivate() {
        isActive = false;
    }

    public void registerPortfolio(PortfolioId portfolioId) {
        portfolios.add(portfolioId);
    }
}
