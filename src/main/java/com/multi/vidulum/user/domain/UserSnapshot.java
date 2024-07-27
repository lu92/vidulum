package com.multi.vidulum.user.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.security.Role;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Value;

import java.util.List;

@Value
public class UserSnapshot implements EntitySnapshot<UserId> {
    UserId userId;
    String username;
    String password;
    String email;
    Role role;
    boolean isActive;
    List<PortfolioId> portfolios;

    @Override
    public UserId id() {
        return userId;
    }
}
