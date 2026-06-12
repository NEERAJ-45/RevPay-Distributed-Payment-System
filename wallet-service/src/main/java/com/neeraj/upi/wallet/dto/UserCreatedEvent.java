package com.neeraj.upi.wallet.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserCreatedEvent {
    @NotNull(message = "User ID is required")
    private UUID userId;
    @NotBlank(message = "UPI ID is required")
    private String upiId;
    @NotBlank(message = "Full name is required")
    private String fullName;
    @NotBlank(message = "Phone number is required")
    private String phone;
    @NotNull(message = "Created at is required")
    private Instant createdAt;
}
