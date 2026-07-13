package com.neeraj.upi.user.service;

import com.neeraj.upi.user.repository.UserRepository;
import org.springframework.stereotype.Component;

@Component
public class UpiIdGeneratorImpl implements UpiIdGenerator {

    private static final String DOMAIN = "@miniupi";

    private final UserRepository userRepository;

    public UpiIdGeneratorImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Generates a unique UPI ID (VPA) from the user's full name.
     * Format: firstword@miniupi — e.g. neeraj@miniupi
     * If taken, appends numeric suffix: neeraj2@miniupi, neeraj3@miniupi, …
     */
    @Override
    public String generate(String fullName) {
        // Sanitize fullName: remove all non-alphanumeric characters and convert to lowercase
        String sanitized = fullName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();

        // Extract first word from original fullName (split by whitespace)
        String firstWord   = fullName.trim().split("\\s+")[0];
        String basePrefix  = firstWord.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();

        // If first word had no valid characters, fallback to fully sanitized name
        if (basePrefix.isEmpty()) {
            if (sanitized.isEmpty()) {
                throw new IllegalArgumentException(
                        "Full name contains no valid alphanumeric characters");
            }
            basePrefix = sanitized;
        }

        // Initial candidate without suffix
        String candidate = basePrefix + DOMAIN;
        int    suffix    = 2;

        // Check uniqueness and add suffix if necessary
        while (userRepository.existsByUpiId(candidate)) {
            candidate = basePrefix + suffix + DOMAIN;
            suffix++;
        }

        return candidate;
    }
}
