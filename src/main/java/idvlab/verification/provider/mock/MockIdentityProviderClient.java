package idvlab.verification.provider.mock;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import idvlab.verification.entity.IdentityProviderType;
import idvlab.verification.provider.IdentityVerificationProvider;
import idvlab.verification.provider.ProviderOutcome;
import idvlab.verification.provider.ProviderResult;
import idvlab.verification.provider.ProviderTransaction;
import idvlab.verification.provider.signature.ResultSignatureVerifier;
import idvlab.verification.service.IdentityVerificationAuditLog;
import idvlab.verification.IdentityVerificationErrorCode;
import idvlab.common.BusinessException;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * 로컬 모의 본인확인기관(lab/mock-identity-provider) HTTP 클라이언트.
 * 흐름 구조는 카카오뱅크 인증서 공개 가이드를 참고했다: 접근토큰 발급 → 인증 요청 → 결과 요청(polling).
 * 실제 카카오뱅크 API를 호출하지 않는다.
 */
@Slf4j
@Component
public class MockIdentityProviderClient implements IdentityVerificationProvider {

    private static final DateTimeFormatter DTTM = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String TOKEN_EXPIRED = "MV9102";
    // 트랜잭션 상태를 담는 결과코드 → 반드시 서명 검증 대상
    private static final Set<String> SIGNED_CODES = Set.of("MV0000", "MV0100", "MV1000", "MV1001", "MV1002", "MV1004");

    private final MockIdentityProviderProperties properties;
    private final WebClient webClient;
    private final ResultSignatureVerifier signatureVerifier;
    private final IdentityVerificationAuditLog auditLog;

    private volatile CachedToken cachedToken;

    private record CachedToken(String value, LocalDateTime expiresAt) {
        boolean usableAt(LocalDateTime now) {
            return now.isBefore(expiresAt.minusSeconds(30));
        }
    }

    public MockIdentityProviderClient(MockIdentityProviderProperties properties, Clock clock,
                                      IdentityVerificationAuditLog auditLog) {
        this.properties = properties;
        this.auditLog = auditLog;
        this.signatureVerifier = new ResultSignatureVerifier(loadTrustAnchor(properties.trustAnchor()),
                properties.expectedSignerCn(), clock);
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
                .responseTimeout(properties.responseTimeout());
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public IdentityProviderType type() {
        return IdentityProviderType.MOCK;
    }

    @Override
    public ProviderTransaction register(String clientRequestId, String callbackUrl) {
        MockIdpResponse response = callWithToken("/mock-idp/v1/identifications", Map.of(
                "corp_id", properties.corpId(),
                "client_request_id", clientRequestId,
                "client_callback_url", callbackUrl,
                "req_service_name", "본인확인 실습"
        ));
        if (!"MV0000".equals(response.responseCode()) || response.result() == null) {
            log.warn("[모의 본인확인] 요청 등록 실패 code={}, message={}", response.responseCode(), response.message());
            throw unavailable();
        }
        MockIdpResponse.Result r = response.result();
        return new ProviderTransaction(r.compositeKey(), r.standardWebUrl(), parse(r.expireDttm()));
    }

    @Override
    public ProviderResult fetchResult(String providerTransactionId, String clientRequestId) {
        return toResult(callWithToken("/mock-idp/v1/identifications/" + providerTransactionId + "/result", Map.of()),
                clientRequestId);
    }

    @Override
    public ProviderResult cancel(String providerTransactionId, String clientRequestId) {
        return toResult(callWithToken("/mock-idp/v1/identifications/" + providerTransactionId + "/cancel", Map.of()),
                clientRequestId);
    }

    // 결과코드 → 의미. 의미를 알 수 없는 코드는 "확정 불가(장애)"로 취급해 실패로 단정하지 않는다
    private ProviderResult toResult(MockIdpResponse response, String clientRequestId) {
        String code = response.responseCode();
        if (SIGNED_CODES.contains(code)) {
            verifyIntegrity(response, clientRequestId);
        }
        ProviderOutcome outcome = switch (code == null ? "" : code) {
            case "MV0000" -> ProviderOutcome.SUCCEEDED;
            case "MV0100" -> ProviderOutcome.PENDING;
            case "MV1000" -> ProviderOutcome.USER_CANCELLED;
            case "MV1001" -> ProviderOutcome.EXPIRED;
            case "MV1002" -> ProviderOutcome.FAILED;
            case "MV1004" -> ProviderOutcome.CLIENT_CANCELLED;
            case "MV9106" -> ProviderOutcome.NOT_FOUND;
            default -> {
                log.warn("[모의 본인확인] 해석할 수 없는 결과코드 code={}, message={}", code, response.message());
                throw unavailable();
            }
        };
        LocalDateTime completedAt = response.result() == null ? null : parse(response.result().completedDttm());
        return new ProviderResult(outcome, code, completedAt);
    }

    /** 서명 검증 + "이 요청의 결과인가" 대조. 실패하면 결과를 반영하지 않는다 */
    private void verifyIntegrity(MockIdpResponse response, String clientRequestId) {
        MockIdpResponse.Result r = response.result();
        try {
            if (r == null) {
                throw new ResultSignatureVerifier.VerificationException(
                        ResultSignatureVerifier.Failure.MISSING_SIGNATURE, "result 없음");
            }
            String signingInput = String.join("|", "MVSIG1", properties.corpId(), nullToEmpty(r.trxKey()),
                    nullToEmpty(r.clientRequestId()), response.responseCode(), nullToEmpty(r.status()),
                    nullToEmpty(r.completedDttm()));
            signatureVerifier.verify(signingInput, r.signedData(), r.signerCert(), r.signAlg());
        } catch (ResultSignatureVerifier.VerificationException e) {
            auditLog.integrityFailure(clientRequestId, e.getFailure().name(), e.getMessage());
            throw new BusinessException(IdentityVerificationErrorCode.IDENTITY_RESULT_INTEGRITY_FAILED);
        }
        if (!clientRequestId.equals(r.clientRequestId())) {
            auditLog.integrityFailure(clientRequestId, "REQUEST_MISMATCH", "응답의 client_request_id=" + r.clientRequestId());
            throw new BusinessException(IdentityVerificationErrorCode.IDENTITY_RESULT_INTEGRITY_FAILED);
        }
    }

    private static java.security.cert.X509Certificate loadTrustAnchor(String location) {
        try (InputStream in = new DefaultResourceLoader().getResource(location).getInputStream()) {
            return ResultSignatureVerifier.readCertificate(in);
        } catch (Exception e) {
            throw new IllegalStateException("[LAB] 결과 서명 검증용 Root CA를 읽지 못했습니다: " + location
                    + " — lab/pki/generate-lab-pki.sh 를 먼저 실행하세요.", e);
        }
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private MockIdpResponse callWithToken(String path, Map<String, Object> body) {
        MockIdpResponse response = post(path, body, accessToken());
        if (TOKEN_EXPIRED.equals(response.responseCode())) {
            // 접근토큰 만료 → 1회만 재발급 후 재시도
            cachedToken = null;
            response = post(path, body, accessToken());
        }
        return response;
    }

    private String accessToken() {
        CachedToken token = cachedToken;
        if (token != null && token.usableAt(LocalDateTime.now())) {
            return token.value();
        }
        MockIdpResponse response = post("/mock-idp/v1/corp/auth", Map.of(
                "corp_id", properties.corpId(),
                "credential", properties.credential()
        ), null);
        if (!"MV0000".equals(response.responseCode()) || response.result() == null) {
            log.error("[모의 본인확인] 접근토큰 발급 실패 code={}", response.responseCode());
            throw unavailable();
        }
        CachedToken issued = new CachedToken(response.result().accessToken(), parse(response.result().expireDttm()));
        cachedToken = issued;
        return issued.value();
    }

    private MockIdpResponse post(String path, Map<String, Object> body, String token) {
        return webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .bodyValue(body)
                .exchangeToMono(res -> {
                    if (res.statusCode().is5xxServerError()) {
                        return res.releaseBody().then(Mono.error(unavailable()));
                    }
                    // 4xx도 본문의 결과코드로 의미를 판단한다 (예: MV9106 존재하지 않는 트랜잭션)
                    return res.bodyToMono(MockIdpResponse.class);
                })
                .switchIfEmpty(Mono.error(unavailable()))
                .onErrorMap(ex -> !(ex instanceof BusinessException), ex -> {
                    log.warn("[모의 본인확인] 호출 실패 path={} cause={}", path, ex.toString());
                    return unavailable();
                })
                .block();
    }

    private static BusinessException unavailable() {
        return new BusinessException(IdentityVerificationErrorCode.IDENTITY_PROVIDER_UNAVAILABLE);
    }

    private static LocalDateTime parse(String dttm) {
        return dttm == null ? null : LocalDateTime.parse(dttm, DTTM);
    }
}
