package com.neeraj.upi.wallet.service;

import com.neeraj.upi.wallet.dto.AddMoneyRequest;
import com.neeraj.upi.wallet.dto.TransferRequest;
import com.neeraj.upi.wallet.dto.WalletResponse;
import com.neeraj.upi.wallet.entity.LedgerEntry;
import com.neeraj.upi.wallet.entity.Wallet;
import com.neeraj.upi.wallet.exception.InsufficientFundsException;
import com.neeraj.upi.wallet.repository.LedgerRepository;
import com.neeraj.upi.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private LedgerRepository ledgerRepository;

    @Captor
    private ArgumentCaptor<LedgerEntry> ledgerCaptor;

    private MeterRegistry meterRegistry;
    private WalletService walletService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        walletService = new WalletService(walletRepository, ledgerRepository, meterRegistry);
    }

    @Test
    public void testCreateWallet() {
        UUID userId = UUID.randomUUID();
        when(walletRepository.existsByUserId(userId)).thenReturn(false);

        walletService.createWallet(userId, "test@upi");

        verify(walletRepository).save(any(Wallet.class));
    }

    @Test
    public void testCreateWalletIdempotent() {
        UUID userId = UUID.randomUUID();
        when(walletRepository.existsByUserId(userId)).thenReturn(true);

        walletService.createWallet(userId, "test@upi");

        verify(walletRepository, never()).save(any(Wallet.class));
    }

    @Test
    public void testAddMoney() {
        UUID walletId = UUID.randomUUID();
        Wallet wallet = Wallet.builder()
                .id(walletId)
                .userId(UUID.randomUUID())
                .upiId("test@upi")
                .balance(BigDecimal.valueOf(500))
                .build();

        when(walletRepository.findByUpiId("test@upi")).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        AddMoneyRequest request = new AddMoneyRequest();
        request.setAmount(BigDecimal.valueOf(200));

        WalletResponse response = walletService.addMoney("test@upi", request);

        assertEquals(BigDecimal.valueOf(700), response.getBalance());
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertEquals(LedgerEntry.EntryType.CREDIT, ledgerCaptor.getValue().getType());
        assertEquals(BigDecimal.valueOf(200), ledgerCaptor.getValue().getAmount());
    }

    @Test
    public void testTransferSuccess() {
        UUID senderWalletId = UUID.randomUUID();
        UUID receiverWalletId = UUID.randomUUID();

        Wallet sender = Wallet.builder()
                .id(senderWalletId)
                .userId(UUID.randomUUID())
                .upiId("sender@upi")
                .balance(BigDecimal.valueOf(1000))
                .build();

        Wallet receiver = Wallet.builder()
                .id(receiverWalletId)
                .userId(UUID.randomUUID())
                .upiId("receiver@upi")
                .balance(BigDecimal.valueOf(500))
                .build();

        when(walletRepository.findByUpiId("sender@upi")).thenReturn(Optional.of(sender));
        when(walletRepository.findByUpiId("receiver@upi")).thenReturn(Optional.of(receiver));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        TransferRequest request = new TransferRequest();
        request.setTransactionId(UUID.randomUUID());
        request.setFromUpiId("sender@upi");
        request.setToUpiId("receiver@upi");
        request.setAmount(BigDecimal.valueOf(200));

        walletService.transfer(request);

        assertEquals(BigDecimal.valueOf(800), sender.getBalance());
        assertEquals(BigDecimal.valueOf(700), receiver.getBalance());
        verify(ledgerRepository, times(2)).save(any(LedgerEntry.class));
    }

    @Test
    public void testTransferInsufficientFunds() {
        Wallet sender = Wallet.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .upiId("sender@upi")
                .balance(BigDecimal.valueOf(50))
                .build();

        when(walletRepository.findByUpiId("sender@upi")).thenReturn(Optional.of(sender));

        TransferRequest request = new TransferRequest();
        request.setTransactionId(UUID.randomUUID());
        request.setFromUpiId("sender@upi");
        request.setToUpiId("receiver@upi");
        request.setAmount(BigDecimal.valueOf(200));

        assertThrows(InsufficientFundsException.class, () -> walletService.transfer(request));
        verify(ledgerRepository, never()).save(any());
    }
}
