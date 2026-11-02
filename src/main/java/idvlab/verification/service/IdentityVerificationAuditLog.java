package idvlab.verification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 본인확인 감사(audit) 로그.
 * 운영 매뉴얼의 장애 분석·민원 대응에서 "언제, 어떤 요청이, 왜 그 상태가 됐는가"를 한 줄로 추적하기 위한 고정 형식.
 *   IDV_AUDIT event=<이벤트> verificationId=<id> key=value ...
 * 개인정보(이름·이메일·전화번호)와 토큰·서명값은 남기지 않는다. 회원은 내부 memberId로만 기록한다.
 */
@Component
public class IdentityVerificationAuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("IDV_AUDIT");

    public void created(String verificationId, Long memberId) {
        AUDIT.info("IDV_AUDIT event=CREATED verificationId={} memberId={}", verificationId, memberId);
    }

    public void registered(String verificationId, String providerTransactionId) {
        AUDIT.info("IDV_AUDIT event=PROVIDER_REGISTERED verificationId={} providerTx={}", verificationId, providerTransactionId);
    }

    public void finished(String verificationId, Long memberId, String status, String reason, String providerCode) {
        AUDIT.info("IDV_AUDIT event=FINISHED verificationId={} memberId={} status={} reason={} providerCode={}",
                verificationId, memberId, status, reason, providerCode);
    }

    public void accountVerified(String verificationId, Long memberId) {
        AUDIT.info("IDV_AUDIT event=ACCOUNT_VERIFIED verificationId={} memberId={}", verificationId, memberId);
    }

    public void withdrawalCancelled(Long memberId, int count) {
        AUDIT.info("IDV_AUDIT event=FINISHED_BY_WITHDRAWAL memberId={} status=CANCELLED reason=MEMBER_WITHDRAWN count={}", memberId, count);
    }

    public void providerUnavailable(String verificationId, String phase) {
        AUDIT.warn("IDV_AUDIT event=PROVIDER_UNAVAILABLE verificationId={} phase={}", verificationId, phase);
    }

    public void integrityFailure(String verificationId, String failure, String detail) {
        AUDIT.error("IDV_AUDIT event=INTEGRITY_FAILURE verificationId={} failure={} detail=\"{}\"", verificationId, failure, detail);
    }
}
