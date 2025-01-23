package io.droptracker.api;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class FernetDecrypt {
    private static final String ENCRYPTION_KEY = "bKWn6PnBLfJHNM7nrxuotRTcn3hzHnl-eANL54Il2Hw=";
    private static final byte VERSION = (byte) 0x80;

    @Inject
    public FernetDecrypt() {
    }

    public static String decryptWebhook(String webhookHash) throws Exception {
        // First decode - get the Fernet token
        String fernetToken = new String(Base64.getUrlDecoder().decode(webhookHash), StandardCharsets.UTF_8);
        
        // Second decode - get the encrypted data
        byte[] token = Base64.getUrlDecoder().decode(fernetToken);
        
        // Decode the key
        byte[] keyBytes = Base64.getUrlDecoder().decode(ENCRYPTION_KEY);
        byte[] signingKey = Arrays.copyOfRange(keyBytes, 0, 16);
        byte[] encryptionKey = Arrays.copyOfRange(keyBytes, 16, 32);

        // Extract components
        ByteBuffer buffer = ByteBuffer.wrap(token);
        byte version = buffer.get();
        long timestamp = buffer.getLong();
        byte[] iv = new byte[16];
        buffer.get(iv);
        
        // Get the HMAC (last 32 bytes)
        byte[] hmac = Arrays.copyOfRange(token, token.length - 32, token.length);
        byte[] message = Arrays.copyOfRange(token, 0, token.length - 32);
        
        // Verify HMAC
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        byte[] calculatedHmac = mac.doFinal(message);
        
        if (!Arrays.equals(hmac, calculatedHmac)) {
            throw new SecurityException("Invalid HMAC");
        }

        // Get ciphertext (everything between IV and HMAC)
        byte[] ciphertext = Arrays.copyOfRange(token, 25, token.length - 32);
        
        // Decrypt
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, 
                   new SecretKeySpec(encryptionKey, "AES"),
                   new IvParameterSpec(iv));
        
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}