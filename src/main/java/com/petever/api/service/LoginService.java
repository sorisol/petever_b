package com.petever.api.service;

import java.util.Locale;

import com.petever.api.entity.User;
import com.petever.api.repository.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Profile("supabase")
public class LoginService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String dummyHash;

    public LoginService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
        this.dummyHash = encoder.encode("not-a-real-account-password");
    }

    public User authenticate(String email, String password) {
        if (email == null || password == null || email.isBlank() || password.isBlank()) {
            throw new InvalidCredentialsException();
        }
        User user = users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).orElse(null);
        boolean active = user != null && "ACTIVE".equals(user.status);
        boolean matches = encoder.matches(password, active ? user.passwordHash : dummyHash);
        if (!active || !matches) throw new InvalidCredentialsException();
        return user;
    }
}