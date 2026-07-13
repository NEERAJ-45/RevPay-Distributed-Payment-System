package com.neeraj.upi.transaction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neeraj.upi.transaction.dto.PayRequest;
import com.neeraj.upi.transaction.dto.PayResponse;
import com.neeraj.upi.transaction.entity.Transaction;
import com.neeraj.upi.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PayController.class)
public class PayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionService transactionService;

    @Test
    public void testPayEndpoint() throws Exception {
        PayRequest request = new PayRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setToUpiId("receiver@upi");
        request.setAmount(BigDecimal.valueOf(100));

        PayResponse response = PayResponse.builder()
                .txnId(UUID.randomUUID())
                .requestId(request.getRequestId())
                .senderUpiId("sender@upi")
                .receiverUpiId("receiver@upi")
                .amount(BigDecimal.valueOf(100))
                .status(Transaction.TransactionStatus.PENDING)
                .replayed(false)
                .build();

        when(transactionService.pay(any(PayRequest.class), anyString())).thenReturn(response);

        mockMvc.perform(post("/transactions/pay")
                        .header("Authorization", "Bearer test-jwt-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.requestId").value(request.getRequestId()));
    }
}
