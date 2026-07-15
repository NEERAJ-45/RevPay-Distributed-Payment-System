package com.neeraj.upi.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neeraj.upi.user.dto.LoginRequest;
import com.neeraj.upi.user.dto.RegisterRequest;
import com.neeraj.upi.user.exception.GlobalExceptionHandler;
import com.neeraj.upi.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private AuthController authController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("POST /api/auth/register should return 201 Created and AuthResponse")
    void registerEndpoint_success() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("John Doe");
        req.setPhone("9876543210");
        req.setPin("1234");

        when(userService.register(any())).thenReturn(null);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/auth/login should return 200 OK and AuthResponse")
    void loginEndpoint_success() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setPhone("9876543210");
        req.setPin("1234");

        when(userService.login(any())).thenReturn(null);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/auth/register should return 400 for invalid payload")
    void registerEndpoint_returns400ForInvalidPayload() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("");  // @NotBlank violation
        req.setPhone("123");  // @Pattern violation
        req.setPin("");       // @NotBlank violation

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/auth/login should return 400 for invalid payload")
    void loginEndpoint_returns400ForInvalidPayload() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setPhone("");     // @NotBlank violation
        req.setPin("");       // @NotBlank violation

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
