package com.neeraj.upi.user.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
@Slf4j
public class QrCodeServiceImpl implements QrCodeService {

    private static final int QR_CODE_HEIGHT = 300;
    private static final int QR_CODE_WIDTH  = 300;

    /**
     * Generates a Base64-encoded PNG QR code for the given UPI ID.
     * UPI URI format: upi://pay?pa={upiId}&pn={name}&cu=INR
     */
    @Override
    public String generateQrCodeBase64(String upiId, String name) {

        try {

            String qrPayload = buildQrPayload(upiId, name);

            BitMatrix bitMatrix = generateQrMatrix(qrPayload);

            byte[] qrImageBytes = generateQrImageBytes(bitMatrix);

            return Base64.getEncoder().encodeToString(qrImageBytes);

        } catch (WriterException exception) {

            log.error("Failed to generate QR code for upiId={}", upiId, exception);

            throw new RuntimeException("Unable to generate QR code");

        } catch (IOException exception) {

            log.error("Failed to process QR image for upiId={}", upiId, exception);

            throw new RuntimeException("Unable to process QR image");
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String buildQrPayload(String upiId, String name) {

        String encodedName =
                URLEncoder.encode(name, StandardCharsets.UTF_8);

        return "upi://pay?pa="
                + upiId
                + "&pn="
                + encodedName
                + "&cu=INR";
    }

    private BitMatrix generateQrMatrix(String qrPayload)
            throws WriterException {

        QRCodeWriter qrCodeWriter = new QRCodeWriter();

        return qrCodeWriter.encode(
                qrPayload,
                BarcodeFormat.QR_CODE,
                QR_CODE_WIDTH,
                QR_CODE_HEIGHT
        );
    }

    private byte[] generateQrImageBytes(BitMatrix bitMatrix)
            throws IOException {

        BufferedImage bufferedImage =
                MatrixToImageWriter.toBufferedImage(bitMatrix);

        ByteArrayOutputStream outputStream =
                new ByteArrayOutputStream();

        ImageIO.write(bufferedImage, "PNG", outputStream);

        return outputStream.toByteArray();
    }
}
