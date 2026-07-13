package com.neeraj.upi.wallet.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neeraj.upi.wallet.dto.AddMoneyRequest;
import com.neeraj.upi.wallet.dto.TransferRequest;
import com.neeraj.upi.wallet.dto.WalletResponse;
import com.neeraj.upi.wallet.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WalletController.class)
public class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WalletService walletService;

    @Test
    public void testGetBalance() throws Exception {
        WalletResponse response = WalletResponse.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .upiId("test@upi")
                .balance(BigDecimal.valueOf(500))
                .createdAt(Instant.now())
                .build();

        when(walletService.getBalance(anyString())).thenReturn(response);

        mockMvc.perform(get("/wallet/balance/test@upi")
                        .header("Authorization", "Bearer test-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.upiId").value("test@upi"))
                .andExpect(jsonPath("$.data.balance").value(500));
    }

    @Test
    public void testAddMoney() throws Exception {
        AddMoneyRequest request = new AddMoneyRequest();
        request.setAmount(BigDecimal.valueOf(200));

        WalletResponse response = WalletResponse.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .upiId("test@upi")
                .balance(BigDecimal.valueOf(700))
                .createdAt(Instant.now())
                .build();

        when(walletService.addMoney(anyString(), any(AddMoneyRequest.class))).thenReturn(response);

        mockMvc.perform(post("/wallet/add-money/test@upi")
                        .header("Authorization", "Bearer test-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.balance").value(700));
    }

    @Test
    public void testTransfer() throws Exception {
        TransferRequest request = new TransferRequest();
        request.setTransactionId(UUID.randomUUID());
        request.setFromUpiId("sender@upi");
        request.setToUpiId("receiver@upi");
        request.setAmount(BigDecimal.valueOf(100));

        mockMvc.perform(post("/wallet/transfer")
                        .header("Authorization", "Bearer test-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
