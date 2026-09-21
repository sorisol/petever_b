package com.petever.api.service;

import java.util.Locale;

import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petever.api.entity.User;
import com.petever.api.repository.UserRepository;

@Service
@Profile("supabase")
public class UserSignupService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserSignupService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User signup(String email, String password, String passwordConfirmation,
                       String nickname, String phone) {
        var user = new User();
        user.email = email.trim().toLowerCase(Locale.ROOT);
        user.passwordHash = passwordEncoder.encode(password);
        user.nickname = nickname.trim();
        user.phone = phone == null || phone.isBlank() ? null : phone.trim();
        return users.saveAndFlush(user);
    }
}
