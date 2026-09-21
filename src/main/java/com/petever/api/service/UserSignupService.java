package com.petever.api.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petever.api.entity.User;
import com.petever.api.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;

@Service
@Profile("supabase")
public class UserSignupService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserSignupService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User signup(String email, String password, String passwordConfirmation,
                       String nickname, String phone) {
        email = normalizeEmail(email);
        nickname = normalize(nickname);
        phone = normalizeNullable(phone);
        validate(email, password, passwordConfirmation, nickname, phone);
        if (users.existsByEmail(email)) throw new DuplicateEmailException();

        var user = new User();
        user.email = email;
        user.passwordHash = passwordEncoder.encode(password);
        user.nickname = nickname;
        user.phone = phone;
        try {
            return users.saveAndFlush(user);
        }
        catch (DataIntegrityViolationException error) {
            if (isEmailUniqueViolation(error)) throw new DuplicateEmailException();
            throw error;
        }
    }

    private static void validate(String email, String password, String confirmation,
                                 String nickname, String phone) {
        var errors = new LinkedHashMap<String, String>();
        if (email.isEmpty() || email.length() > 254 || !EMAIL.matcher(email).matches())
            errors.put("email", "올바른 이메일을 입력해 주세요.");
        if (password == null || password.codePointCount(0, password.length()) < 8)
            errors.put("password", "비밀번호는 8자 이상이어야 합니다.");
        else if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            errors.put("password", "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        if (password == null || !password.equals(confirmation))
            errors.put("passwordConfirmation", "비밀번호가 일치하지 않습니다.");
        int nicknameLength = nickname.codePointCount(0, nickname.length());
        if (nicknameLength < 2 || nicknameLength > 50)
            errors.put("nickname", "닉네임은 2자 이상 50자 이하여야 합니다.");
        if (phone != null && phone.codePointCount(0, phone.length()) > 30)
            errors.put("phone", "전화번호는 30자 이하여야 합니다.");
        if (!errors.isEmpty()) throw new SignupValidationException(errors);
    }

    private static boolean isEmailUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && constraint.getSQLException() != null
                    && "23505".equals(constraint.getSQLException().getSQLState())
                    && "users_email_key".equals(constraint.getConstraintName())) return true;
        }
        return false;
    }

    private static String normalizeEmail(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeNullable(String value) {
        var normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }
}
