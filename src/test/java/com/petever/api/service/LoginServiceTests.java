package com.petever.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.petever.api.entity.User;
import com.petever.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class LoginServiceTests {
    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final LoginService login = new LoginService(users, encoder);

    @Test
    void normalizesEmailAndVerifiesBcryptForActiveAccount() {
        var user = new User();
        user.email = "member@example.com";
        user.status = "ACTIVE";
        user.passwordHash = encoder.encode("valid-password");
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));

        assertEquals(user, login.authenticate("  MEMBER@EXAMPLE.COM  ", "valid-password"));
        assertThrows(InvalidCredentialsException.class,
                () -> login.authenticate("member@example.com", "wrong-password"));
    }

    @Test
    void rejectsMissingAndInactiveAccountsWithoutRevealingWhichFailed() {
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        var inactive = new User();
        inactive.status = "INACTIVE";
        inactive.passwordHash = encoder.encode("valid-password");
        when(users.findByEmail("inactive@example.com")).thenReturn(Optional.of(inactive));

        assertThrows(InvalidCredentialsException.class,
                () -> login.authenticate("missing@example.com", "valid-password"));
        assertThrows(InvalidCredentialsException.class,
                () -> login.authenticate("inactive@example.com", "valid-password"));
    }
}