package com.neeraj.upi.transaction.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class TransferRequest {
    private UUID transactionId;
    private String fromUpiId;
    private String toUpiId;
    private BigDecimal amount;
    private String note;
}
