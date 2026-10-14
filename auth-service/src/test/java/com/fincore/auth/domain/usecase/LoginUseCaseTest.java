package com.fincore.auth.domain.usecase;

import com.fincore.auth.domain.exception.InvalidCredentialsException;
import com.fincore.auth.domain.model.User;
import com.fincore.auth.domain.port.PasswordEncoder;
import com.fincore.auth.domain.port.TokenGenerator;
import com.fincore.auth.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginUseCaseTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenGenerator tokenGenerator;

    private LoginUseCase loginUseCase;

    private final String email = "test@fincore.com";
    private final String rawPassword = "SecurePassword123!";
    private final String encodedPassword = "$2a$10$hashedpassword";

    @BeforeEach
    void setUp() {
        loginUseCase = new LoginUseCase(userRepository, passwordEncoder, tokenGenerator);
    }

    @Test
    @DisplayName("Should generate token when credentials are valid and user is active")
    void execute_WhenCredentialsValid_ShouldReturnToken() {
        User user = new User(UUID.randomUUID(), "Test User", email, encodedPassword, User.Role.CUSTOMER, true, java.time.LocalDateTime.now());
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);
        when(tokenGenerator.generate(user)).thenReturn("mocked.jwt.token");

        String token = loginUseCase.execute(email, rawPassword);

        assertThat(token).isEqualTo("mocked.jwt.token");
        verify(tokenGenerator).generate(user);
    }

    @Test
    @DisplayName("Should throw InvalidCredentialsException when email is not found")
    void execute_WhenEmailNotFound_ShouldThrowException() {
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginUseCase.execute(email, rawPassword))
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(tokenGenerator);
    }

    @Test
    @DisplayName("Should throw InvalidCredentialsException when password does not match")
    void execute_WhenPasswordMismatch_ShouldThrowException() {
        User user = new User(UUID.randomUUID(), "Test User", email, encodedPassword, User.Role.CUSTOMER, true, java.time.LocalDateTime.now());
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(false);

        assertThatThrownBy(() -> loginUseCase.execute(email, rawPassword))
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(tokenGenerator);
    }

    @Test
    @DisplayName("Should throw InvalidCredentialsException when user is inactive")
    void execute_WhenUserInactive_ShouldThrowException() {
        User inactiveUser = new User(UUID.randomUUID(), "Test User", email, encodedPassword, User.Role.CUSTOMER, false, java.time.LocalDateTime.now());
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(inactiveUser));
        when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);

        assertThatThrownBy(() -> loginUseCase.execute(email, rawPassword))
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(tokenGenerator);
    }
}
