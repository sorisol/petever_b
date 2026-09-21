package com.petever.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.petever.api.entity.User;
import com.petever.api.repository.UserRepository;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;

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

    @Test
    void rejectsPasswordLongerThan72Utf8Bytes() {
        var password = "가".repeat(25);

        var error = assertThrows(SignupValidationException.class, () ->
                service.signup("long@example.com", password, password, "긴암호", null));

        assertTrue(error.fieldErrors().containsKey("password"));
    }

    @Test
    void rejectsMismatchedPasswordConfirmation() {
        var error = assertThrows(SignupValidationException.class, () ->
                service.signup("mismatch@example.com", "password-one", "password-two", "불일치", null));

        assertTrue(error.fieldErrors().containsKey("passwordConfirmation"));
    }

    @Test
    void rejectsMalformedEmailAndShortNickname() {
        var error = assertThrows(SignupValidationException.class, () ->
                service.signup("not-an-email", "valid-password", "valid-password", "x", null));

        assertTrue(error.fieldErrors().containsKey("email"));
        assertTrue(error.fieldErrors().containsKey("nickname"));
    }

    @Test
    void rejectsAnExistingNormalizedEmail() {
        when(users.existsByEmail("member@example.com")).thenReturn(true);

        assertThrows(DuplicateEmailException.class, () -> service.signup(
                " MEMBER@EXAMPLE.COM ", "valid-password", "valid-password", "회원", null));
    }

    @Test
    void mapsTheEmailConstraintRaceToDuplicateEmail() {
        var sql = new SQLException("duplicate", "23505");
        var cause = new ConstraintViolationException("duplicate", sql, "users_email_key");
        when(users.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate", cause));

        assertThrows(DuplicateEmailException.class, () -> service.signup(
                "race@example.com", "valid-password", "valid-password", "레이스", null));
    }

    @Test
    void rethrowsAnotherIntegrityViolation() {
        var sql = new SQLException("check", "23514");
        var cause = new ConstraintViolationException("check", sql, "users_status_check");
        var failure = new DataIntegrityViolationException("check", cause);
        when(users.saveAndFlush(any(User.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.signup(
                "other@example.com", "valid-password", "valid-password", "다른오류", null)));
    }
}
