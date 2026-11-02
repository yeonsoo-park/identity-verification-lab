package idvlab.verification.provider.signature;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;

/**
 * 외부 기관 결과의 전자서명 검증 (PKI 학습용 최소 구현).
 *
 * 검증 순서 — 하나라도 실패하면 그 결과는 "의미가 확인되지 않은 응답"으로 취급한다.
 *  1. 서명 알고리즘이 약속된 값(SHA256withRSA)인가
 *  2. 서명자 인증서가 신뢰 기준점(Lab Root CA)이 발급한 것인가          — 체인 검증
 *  3. 서명자 인증서와 Root CA가 지금 유효기간 안인가                      — 유효기간
 *  4. 서명자 인증서가 CA가 아니고, 용도(keyUsage)에 전자서명이 있는가     — 용도 제한
 *  5. 서명자 이름(CN)이 약속된 기관인가                                  — 서명자 고정
 *  6. 서명 원문과 서명값이 일치하는가                                    — 위·변조 탐지
 *
 * 범위 밖(문서화): 인증서 폐지 확인(CRL/OCSP), 중간 CA 다단계 체인, CMS/PKCS#7 봉투, 타임스탬프.
 */
@Slf4j
public class ResultSignatureVerifier {

    public static final String ALGORITHM = "SHA256withRSA";
    private static final int KEY_USAGE_DIGITAL_SIGNATURE = 0;

    public enum Failure {
        MISSING_SIGNATURE, UNSUPPORTED_ALGORITHM, MALFORMED_CERTIFICATE, UNTRUSTED_ISSUER,
        CERTIFICATE_NOT_VALID_NOW, INVALID_KEY_USAGE, UNEXPECTED_SIGNER, SIGNATURE_MISMATCH
    }

    public static class VerificationException extends RuntimeException {
        private final Failure failure;

        public VerificationException(Failure failure, String detail) {
            super(failure + ": " + detail);
            this.failure = failure;
        }

        public Failure getFailure() {
            return failure;
        }
    }

    private final X509Certificate trustAnchor;
    private final String expectedSignerCn;
    private final Clock clock;

    public ResultSignatureVerifier(X509Certificate trustAnchor, String expectedSignerCn, Clock clock) {
        this.trustAnchor = trustAnchor;
        this.expectedSignerCn = expectedSignerCn;
        this.clock = clock;
    }

    public static X509Certificate readCertificate(InputStream pemOrDer) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pemOrDer);
    }

    public void verify(String signingInput, String signatureB64, String signerCertB64, String algorithm) {
        if (signatureB64 == null || signerCertB64 == null) {
            throw new VerificationException(Failure.MISSING_SIGNATURE, "signed_data 또는 signer_cert 없음");
        }
        if (!ALGORITHM.equals(algorithm)) {
            throw new VerificationException(Failure.UNSUPPORTED_ALGORITHM, String.valueOf(algorithm));
        }
        X509Certificate signer;
        try {
            signer = readCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(signerCertB64)));
        } catch (Exception e) {
            throw new VerificationException(Failure.MALFORMED_CERTIFICATE, e.getMessage());
        }
        try {
            signer.verify(trustAnchor.getPublicKey());
        } catch (Exception e) {
            throw new VerificationException(Failure.UNTRUSTED_ISSUER, signer.getIssuerX500Principal().getName());
        }
        try {
            Date now = Date.from(clock.instant());
            trustAnchor.checkValidity(now);
            signer.checkValidity(now);
        } catch (Exception e) {
            throw new VerificationException(Failure.CERTIFICATE_NOT_VALID_NOW, e.getMessage());
        }
        boolean[] keyUsage = signer.getKeyUsage();
        if (signer.getBasicConstraints() != -1 || keyUsage == null || !keyUsage[KEY_USAGE_DIGITAL_SIGNATURE]) {
            throw new VerificationException(Failure.INVALID_KEY_USAGE, "CA 인증서이거나 digitalSignature 용도 없음");
        }
        String cn = commonName(signer);
        if (!expectedSignerCn.equals(cn)) {
            throw new VerificationException(Failure.UNEXPECTED_SIGNER, cn);
        }
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(signer.getPublicKey());
            sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(Base64.getDecoder().decode(signatureB64))) {
                throw new VerificationException(Failure.SIGNATURE_MISMATCH, "서명 원문과 서명값 불일치");
            }
        } catch (VerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new VerificationException(Failure.SIGNATURE_MISMATCH, e.getMessage());
        }
    }

    private static String commonName(X509Certificate cert) {
        for (String part : cert.getSubjectX500Principal().getName().split(",")) {
            String p = part.trim();
            if (p.startsWith("CN=")) return p.substring(3);
        }
        return "";
    }
}
