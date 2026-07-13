package com.neeraj.upi.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neeraj.upi.user.dto.*;
import com.neeraj.upi.user.entity.OutboxEvent;
import com.neeraj.upi.user.entity.User;
import com.neeraj.upi.user.event.UserCreatedEvent;
import com.neeraj.upi.user.exception.InvalidCredentialsException;
import com.neeraj.upi.user.exception.OutboxSerializationException;
import com.neeraj.upi.user.exception.UserAlreadyExistsException;
import com.neeraj.upi.user.exception.UserNotFoundException;
import com.neeraj.upi.user.repository.OutboxEventRepository;
import com.neeraj.upi.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UpiIdGenerator upiIdGenerator;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest req) {

        validateDuplicatePhone(req.getPhone());

        User savedUser = createAndSaveUser(req);

        saveUserCreatedOutboxEvent(savedUser);

        String token = jwtService.generateToken(
                savedUser.getId(),
                savedUser.getUpiId(),
                savedUser.getPhone());

        log.info("User registered: userId={} upiId={}",
                savedUser.getId(),
                savedUser.getUpiId());

        return AuthResponse.of(
                token,
                savedUser.getUpiId(),
                savedUser.getFullName());
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        // 1. Lookup by phone — use a vague error message to avoid user enumeration
        User user = userRepository.findByPhone(req.getPhone())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid phone number or PIN"));

        // 2. Verify BCrypt PIN
        if (!passwordEncoder.matches(req.getPin(), user.getPinHash())) {
            throw new InvalidCredentialsException("Invalid phone number or PIN");
        }

        // 3. Check account status
        if (!user.isActive()) {
            throw new InvalidCredentialsException("User account is deactivated");
        }

        // 4. Issue JWT
        String token = jwtService.generateToken(user.getId(), user.getUpiId(), user.getPhone());
        log.info("User logged in: userId={} upiId={}", user.getId(), user.getUpiId());
        return AuthResponse.of(token, user.getUpiId(), user.getFullName());
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getByUserId(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found for id: " + userId));
        log.info("Fetching user profile: userId={}", userId);
        return mapToProfileResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getByUpiId(String upiId) {
        User user = userRepository.findByUpiId(upiId)
                .orElseThrow(() -> new UserNotFoundException("User not found for upiId: " + upiId));
        log.info("Fetching user profile: upiId={}", upiId);
        return mapToProfileResponse(user);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private UserProfileResponse mapToProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .email(user.getEmail())
                .upiId(user.getUpiId())
                .isActive(user.isActive())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private void validateDuplicatePhone(String phone) {
        if (userRepository.existsByPhone(phone)) {
            throw new UserAlreadyExistsException(
                    "Phone number already registered: " + phone);
        }
    }

    private User createAndSaveUser(RegisterRequest req) {

        String upiId = upiIdGenerator.generate(req.getFullName());

        String hashedPin = passwordEncoder.encode(req.getPin());

        User user = User.builder()
                .fullName(req.getFullName())
                .phone(req.getPhone())
                .email(req.getEmail())
                .upiId(upiId)
                .pinHash(hashedPin)
                .isActive(true)
                .build();

        return userRepository.save(user);
    }

    private void saveUserCreatedOutboxEvent(User user) {

        UserCreatedEvent event = buildUserCreatedEvent(user);

        try {

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(user.getId().toString())
                    .aggregateType("User")
                    .eventType("user.created")
                    .payload(objectMapper.writeValueAsString(event))
                    .build();

            outboxEventRepository.save(outboxEvent);

        } catch (JsonProcessingException e) {

            throw new OutboxSerializationException(e);
        }
    }

    private UserCreatedEvent buildUserCreatedEvent(User user) {

        return UserCreatedEvent.builder()
                .userId(user.getId())
                .upiId(user.getUpiId())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
