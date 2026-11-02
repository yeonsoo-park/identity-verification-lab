package idvlab.verification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// 유효시간 판단을 테스트에서 재현할 수 있도록 시계를 주입받는다
@Configuration
public class IdentityVerificationClockConfig {

    @Bean
    public Clock identityVerificationClock() {
        return Clock.systemDefaultZone();
    }
}
