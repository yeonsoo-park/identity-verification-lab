package idvlab.verification.provider;

import java.time.LocalDateTime;

public record ProviderResult(ProviderOutcome outcome, String providerCode, LocalDateTime completedAt) {
}
