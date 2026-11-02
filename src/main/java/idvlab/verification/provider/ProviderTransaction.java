package idvlab.verification.provider;

import java.time.LocalDateTime;

public record ProviderTransaction(String transactionId, String authUrl, LocalDateTime expiresAt) {
}
