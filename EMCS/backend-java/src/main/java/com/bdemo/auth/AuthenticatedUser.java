package com.bdemo.auth;

import java.security.Principal;

public record AuthenticatedUser(long userId, String userName) implements Principal {
    @Override
    public String getName() {
        return userName;
    }
}
