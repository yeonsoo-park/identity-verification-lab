package idvlab.verification.provider.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// 모의 본인확인기관 접속 설정 (application.yml). 운영 값을 넣지 않는다
@ConfigurationProperties(prefix = "lab.mock-identity-provider")
public record MockIdentityProviderProperties(
        String baseUrl,
        String corpId,
        String credential,
        Duration connectTimeout,
        Duration responseTimeout,
        String trustAnchor,        // 결과 서명 검증 기준 Root CA 인증서 (예: file:lab/pki/out/lab-root-ca.crt)
        String expectedSignerCn    // 결과에 서명해야 하는 기관 인증서의 CN
) {
}
