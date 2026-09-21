package com.petever.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.petever.api.entity.User;
import com.petever.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserSignupServiceTests {
    @Mock UserRepository users;
    private UserSignupService service;

    @BeforeEach
    void setUp() {
        service = new UserSignupService(users, new BCryptPasswordEncoder(12));
    }

    @Test
    void normalizesAndHashesAnActiveUser() {
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            var user = invocation.getArgument(0, User.class);
            user.id = 7L;
            return user;
        });

        service.signup("  USER@Example.com  ", "correct horse battery staple",
                "correct horse battery staple", "  몽글집사  ", " 010-1234-5678 ");

        var saved = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(users).saveAndFlush(saved.capture());
        assertEquals("user@example.com", saved.getValue().email);
        assertEquals("몽글집사", saved.getValue().nickname);
        assertEquals("010-1234-5678", saved.getValue().phone);
        assertEquals("USER", saved.getValue().systemRole);
        assertEquals("ACTIVE", saved.getValue().status);
        assertTrue(saved.getValue().passwordHash.startsWith("$2a$12$"));
        assertTrue(new BCryptPasswordEncoder(12).matches(
                "correct horse battery staple", saved.getValue().passwordHash));
    }
}
