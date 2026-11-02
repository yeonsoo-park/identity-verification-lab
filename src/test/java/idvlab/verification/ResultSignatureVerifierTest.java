package idvlab.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import idvlab.verification.provider.signature.ResultSignatureVerifier;
import idvlab.verification.provider.signature.ResultSignatureVerifier.Failure;
import idvlab.verification.provider.signature.ResultSignatureVerifier.VerificationException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결과 전자서명 검증 단위 테스트. 픽스처는 Gradle generateTestPki 태스크가 테스트 직전에
 * lab/pki/generate-lab-pki.sh로 build/pki-test에 생성한다 (저장소에 키를 커밋하지 않음, 실제 인증서 아님).
 */
class ResultSignatureVerifierTest {

    private static final String INPUT = "MVSIG1|IDV-LAB|trx1|req-1|MV0000|SUCCEEDED|20261102230000";
    private static final String SIGNER_CN = "Mock IdP Result Signing";

    private final X509Certificate root = cert("lab-root-ca.crt");
    private final ResultSignatureVerifier verifier =
            new ResultSignatureVerifier(root, SIGNER_CN, Clock.systemDefaultZone());

    @Test
    @DisplayName("신뢰 체계 안의 서명자가 원문 그대로 서명했으면 통과")
    void valid() {
        assertThatCode(() -> verifier.verify(INPUT, sign(INPUT, "mock-idp-signing.key"), certB64("mock-idp-signing.crt"),
                ResultSignatureVerifier.ALGORITHM)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("서명 후 값이 바뀌면(예: FAILED→SUCCEEDED) 서명 불일치")
    void tampered() {
        String sig = sign(INPUT.replace("SUCCEEDED", "FAILED"), "mock-idp-signing.key");
        expect(Failure.SIGNATURE_MISMATCH, () -> verifier.verify(INPUT, sig, certB64("mock-idp-signing.crt"), ResultSignatureVerifier.ALGORITHM));
    }

    @Test
    @DisplayName("Root CA가 발급하지 않은 인증서(이름만 같은 자체 서명)는 신뢰하지 않음")
    void untrustedIssuer() {
        String sig = sign(INPUT, "rogue-signing.key");
        expect(Failure.UNTRUSTED_ISSUER, () -> verifier.verify(INPUT, sig, certB64("rogue-signing.crt"), ResultSignatureVerifier.ALGORITHM));
    }

    @Test
    @DisplayName("인증서 유효기간이 지난 시점에는 거부")
    void expiredCertificate() {
        Clock twoYearsLater = Clock.fixed(Instant.now().plus(Duration.ofDays(730)), ZoneId.systemDefault());
        ResultSignatureVerifier later = new ResultSignatureVerifier(root, SIGNER_CN, twoYearsLater);
        String sig = sign(INPUT, "mock-idp-signing.key");
        expect(Failure.CERTIFICATE_NOT_VALID_NOW, () -> later.verify(INPUT, sig, certB64("mock-idp-signing.crt"), ResultSignatureVerifier.ALGORITHM));
    }

    @Test
    @DisplayName("CA 인증서로는 결과에 서명할 수 없음 (용도 제한)")
    void caCertificateCannotSign() {
        expect(Failure.INVALID_KEY_USAGE, () -> verifier.verify(INPUT, "AAAA", certB64("lab-root-ca.crt"), ResultSignatureVerifier.ALGORITHM));
    }

    @Test
    @DisplayName("약속되지 않은 서명자 이름이면 거부 (서명자 고정)")
    void unexpectedSigner() {
        ResultSignatureVerifier pinned = new ResultSignatureVerifier(root, "Other Institution", Clock.systemDefaultZone());
        String sig = sign(INPUT, "mock-idp-signing.key");
        expect(Failure.UNEXPECTED_SIGNER, () -> pinned.verify(INPUT, sig, certB64("mock-idp-signing.crt"), ResultSignatureVerifier.ALGORITHM));
    }

    @Test
    @DisplayName("서명 누락·약속되지 않은 알고리즘은 거부")
    void missingOrWrongAlgorithm() {
        expect(Failure.MISSING_SIGNATURE, () -> verifier.verify(INPUT, null, certB64("mock-idp-signing.crt"), ResultSignatureVerifier.ALGORITHM));
        expect(Failure.UNSUPPORTED_ALGORITHM, () -> verifier.verify(INPUT, "AAAA", certB64("mock-idp-signing.crt"), "SHA1withRSA"));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────
    private static void expect(Failure failure, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(VerificationException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getFailure()).isEqualTo(failure));
    }

    private static Path fixture(String name) {
        return Path.of(System.getProperty("pki.test.dir", "build/pki-test"), name);
    }

    private static X509Certificate cert(String name) {
        try (InputStream in = Files.newInputStream(fixture(name))) {
            return ResultSignatureVerifier.readCertificate(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String certB64(String name) {
        try {
            return Base64.getEncoder().encodeToString(cert(name).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(String text, String keyName) {
        try {
            String pem = Files.readString(fixture(keyName), StandardCharsets.US_ASCII);
            byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            Signature s = Signature.getInstance(ResultSignatureVerifier.ALGORITHM);
            s.initSign(key);
            s.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
