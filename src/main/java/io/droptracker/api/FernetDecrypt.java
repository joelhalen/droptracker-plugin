package io.droptracker.api;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class FernetDecrypt {
    public static String ENCRYPTION_KEY = "";

    @Inject
    public FernetDecrypt() {
    }

    public static String decryptWebhook(String webhookHash) throws Exception {
        try {
            // First decode - get the Fernet token
            String fernetToken = new String(Base64.getUrlDecoder().decode(webhookHash), StandardCharsets.UTF_8);
            
            // Second decode - get the encrypted data
            byte[] token = Base64.getUrlDecoder().decode(fernetToken);
            
            // Decode the key
            byte[] keyBytes = Base64.getUrlDecoder().decode(ENCRYPTION_KEY);
            byte[] signingKey = Arrays.copyOfRange(keyBytes, 0, 16);
            byte[] encryptionKey = Arrays.copyOfRange(keyBytes, 16, 32);

            // Extract components according to Fernet spec:
            // Version (1 byte) + Timestamp (8 bytes) + IV (16 bytes) + Ciphertext + HMAC (32 bytes)
            if (token.length < 57) { // Minimum size: 1+8+16+0+32
                throw new IllegalArgumentException("Token too short: " + token.length);
            }
            
            byte version = token[0];
            
            // Skip timestamp (bytes 1-8) - not needed for decryption
            
            // Extract IV (bytes 9-24)  
            byte[] iv = Arrays.copyOfRange(token, 9, 25);

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
            // Start at byte 25 (after version + timestamp + IV)
            byte[] ciphertext = Arrays.copyOfRange(token, 25, token.length - 32);
            // Decrypt
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, 
                       new SecretKeySpec(encryptionKey, "AES"),
                       new IvParameterSpec(iv));
            
            byte[] decryptedBytes = cipher.doFinal(ciphertext);
            String result = new String(decryptedBytes, StandardCharsets.UTF_8);
            
            // Post-process the result to fix common issues
            result = postProcessDecryptedUrl(result);
            
            return result; 
        } catch (Exception e) {
            log.error("Decryption failed for webhook hash: {}", webhookHash.substring(0, Math.min(20, webhookHash.length())) + "...", e);
            throw e;
        }
    }
    
    /**
     * Post-process decrypted URL to fix common corruption issues
     */
    private static String postProcessDecryptedUrl(String decrypted) {
        if (decrypted == null || decrypted.isEmpty()) {
            return decrypted;
        }
        
        // Remove non-printable characters from the beginning
        String cleaned = decrypted.replaceAll("^[\\p{Cntrl}\\p{So}\\p{Cn}]+", "");
        
        // Look for the webhook path pattern
        int webhookIndex = cleaned.indexOf("/api/webhooks/");
        if (webhookIndex > 0) {
            // Extract just the webhook path part
            cleaned = cleaned.substring(webhookIndex);
        }
        
        // If it starts with /api/webhooks/, prepend the Discord domain
        if (cleaned.startsWith("/api/webhooks/")) {
            cleaned = "https://discord.com" + cleaned;
        }
        
        // If it contains .com/api/webhooks but doesn't start with https://, try to fix it
        if (cleaned.contains("com/api/webhooks/") && !cleaned.startsWith("https://")) {
            int comIndex = cleaned.indexOf("com/api/webhooks/");
            if (comIndex >= 0) {
                cleaned = "https://discord." + cleaned.substring(comIndex);
            }
        }
        return cleaned;
    }
}
