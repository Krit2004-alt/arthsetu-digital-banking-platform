package com.fincore.auth.domain.usecase;

import com.fincore.auth.domain.event.UserRegisteredEvent;
import com.fincore.auth.domain.exception.EmailAlreadyExistsException;
import com.fincore.auth.domain.model.User;
import com.fincore.auth.domain.port.EventPublisher;
import com.fincore.auth.domain.port.PasswordEncoder;
import com.fincore.auth.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegisterUserUseCaseTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private PasswordEncoder passwordEncoder;

    private RegisterUserUseCase registerUserUseCase;

    private final String name = "Jesus Pacheco";
    private final String email = "jesus@fincore.com";
    private final String rawPassword = "SecurePass123!";
    private final String encodedPassword = "$2a$10$hashedSecurePass123";

    @BeforeEach
    void setUp() {
        registerUserUseCase = new RegisterUserUseCase(userRepository, eventPublisher, passwordEncoder);
    }

    @Test
    @DisplayName("Should successfully encode password, save user, and publish UserRegisteredEvent")
    void execute_WhenEmailAvailable_ShouldRegisterUser() {
        when(userRepository.existsByEmail(email)).thenReturn(false);
        when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User result = registerUserUseCase.execute(name, email, rawPassword);

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo(email);
        assertThat(result.getName()).isEqualTo(name);
        assertThat(result.getPasswordHash()).isEqualTo(encodedPassword);
        assertThat(result.getRole()).isEqualTo(User.Role.CUSTOMER);
        assertThat(result.isActive()).isTrue();

        verify(userRepository).save(any(User.class));
        verify(eventPublisher).publish(eq("fincore.user.registered"), any(UserRegisteredEvent.class));
    }

    @Test
    @DisplayName("Should throw EmailAlreadyExistsException when email is already registered")
    void execute_WhenEmailAlreadyExists_ShouldThrowException() {
        when(userRepository.existsByEmail(email)).thenReturn(true);

        assertThatThrownBy(() -> registerUserUseCase.execute(name, email, rawPassword))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }
}
