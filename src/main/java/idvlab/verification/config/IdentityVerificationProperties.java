package idvlab.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 본인확인 정책 숫자. 모두 "실습용 가정"이며 실제 인증기관 정책이 아니다.
 *
 * @param attemptTtl        인증 시도 유효시간 (서비스 기준)
 * @param pollInterval      클라이언트에 안내하는 결과 조회 간격
 * @param unconfirmedGrace  유효시간이 지난 뒤에도 외부 결과를 확인하지 못할 때, 만료로 확정하기까지 기다리는 시간
 * @param callbackUrl       외부 인증 화면 종료 후 돌아올 서비스 화면 주소
 */
@ConfigurationProperties(prefix = "lab.identity-verification")
public record IdentityVerificationProperties(
        Duration attemptTtl,
        Duration pollInterval,
        Duration unconfirmedGrace,
        String callbackUrl
) {
}
