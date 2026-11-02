package idvlab.verification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import idvlab.member.Member;
import idvlab.member.MemberService;
import idvlab.verification.config.IdentityVerificationProperties;
import idvlab.verification.dto.IdentityVerificationResponse;
import idvlab.verification.entity.IdentityVerification;
import idvlab.verification.entity.VerificationAttemptStatus;
import idvlab.verification.entity.VerificationEndReason;
import idvlab.verification.provider.IdentityVerificationProvider;
import idvlab.verification.provider.ProviderOutcome;
import idvlab.verification.provider.ProviderResult;
import idvlab.verification.provider.ProviderTransaction;
import idvlab.verification.repository.IdentityVerificationRepository;
import idvlab.common.CommonErrorCode;
import idvlab.verification.IdentityVerificationErrorCode;
import idvlab.common.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 선택형 본인확인 흐름 조율.
 *
 * 원칙
 *  1. 회원은 로그인 토큰의 memberId로만 식별한다. 다른 회원의 요청은 404로 "없는 요청"처럼 응답한다.
 *  2. 클라이언트가 보낸 성공 값은 믿지 않는다. 서버가 외부 기관에 직접 결과를 조회해 확정한다.
 *  3. 외부 호출은 트랜잭션 밖에서, DB 반영은 IdentityVerificationTxService의 짧은 트랜잭션에서 한다.
 *  4. 네트워크 오류는 실패가 아니다. 의미가 확인된 결과만 종료 상태로 확정하고, 종료 상태는 다시 바뀌지 않는다.
 *
 * 충돌 시 기준
 *  - "사용자가 외부 화면에서 무엇을 했는가(성공·실패·취소)"는 외부 기관 응답이 기준
 *  - "유효시간·계정 상태·이미 확정된 종료 상태"는 서비스가 기준 (외부가 뒤늦게 다른 값을 줘도 바꾸지 않음)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityVerificationService {

    private final IdentityVerificationRepository verificationRepository;
    private final IdentityVerificationTxService txService;
    private final IdentityVerificationProvider provider;
    private final IdentityVerificationProperties properties;
    private final MemberService memberService;
    private final IdentityVerificationAuditLog auditLog;
    private final Clock clock;

    public record StartResult(IdentityVerificationResponse response, boolean created) {}

    /**
     * 본인확인 요청 생성.
     * - 이미 확인 완료 계정 → 409
     * - 진행 중(PENDING) 요청이 있으면 새로 만들지 않고 그 요청을 돌려준다 (버튼 연속 클릭·화면 재진입)
     * - 외부 등록 응답이 유실된 요청은 같은 verificationId로 다시 등록한다 (외부 기관이 멱등 처리)
     */
    public StartResult start(Long memberId) {
        Member member = memberService.findById(memberId);
        if (member.isIdentityVerified()) {
            throw new BusinessException(IdentityVerificationErrorCode.IDENTITY_ALREADY_VERIFIED);
        }

        Optional<IdentityVerification> pending = verificationRepository
                .findByMemberIdAndStatus(memberId, VerificationAttemptStatus.PENDING);
        if (pending.isPresent()) {
            IdentityVerification synced = syncQuietly(pending.get());
            if (synced.isPending() && synced.isExpiredAt(now())) {
                // 유효시간은 지났지만 외부 결과를 확인하지 못함 → 새 요청을 만들지 않고 재시도 안내
                throw new BusinessException(IdentityVerificationErrorCode.IDENTITY_PROVIDER_UNAVAILABLE);
            }
            if (synced.isPending()) {
                return new StartResult(toResponse(ensureRegistered(synced), memberId), false);
            }
            if (synced.getStatus() == VerificationAttemptStatus.SUCCEEDED) {
                // 앱으로 돌아오지 못했지만 이미 성공한 경우 → 이번 동기화로 계정이 확인 완료됨
                throw new BusinessException(IdentityVerificationErrorCode.IDENTITY_ALREADY_VERIFIED);
            }
            // 실패·취소·만료로 끝났다면 새 요청을 만든다
        }

        IdentityVerification created;
        try {
            created = txService.createPending(memberId, provider.type(), now(), properties.attemptTtl());
        } catch (DataIntegrityViolationException e) {
            // 동시에 들어온 다른 요청이 먼저 PENDING을 만들었다 → 그 요청을 이어서 사용
            IdentityVerification existing = verificationRepository
                    .findByMemberIdAndStatus(memberId, VerificationAttemptStatus.PENDING)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
            return new StartResult(toResponse(ensureRegistered(existing), memberId), false);
        }
        auditLog.created(created.getVerificationId(), memberId);
        return new StartResult(toResponse(ensureRegistered(created), memberId), true);
    }

    /** 진행 상태 조회 + (진행 중이면) 외부 결과 확인·반영 */
    public IdentityVerificationResponse get(Long memberId, String verificationId) {
        IdentityVerification v = findOwned(memberId, verificationId);
        return toResponse(sync(v), memberId);
    }

    /**
     * 명시적 취소. 화면을 닫는 행동은 이 API를 호출하지 않는다 (요청은 유효시간까지 PENDING 유지).
     * 이미 종료된 요청이면 바꾸지 않고 현재 상태를 돌려준다 (멱등).
     */
    public IdentityVerificationResponse cancel(Long memberId, String verificationId) {
        IdentityVerification v = findOwned(memberId, verificationId);
        if (!v.isPending()) {
            return toResponse(v, memberId);
        }
        if (!v.isRegisteredToProvider()) {
            return toResponse(txService.finish(v.getId(), VerificationAttemptStatus.CANCELLED,
                    VerificationEndReason.USER_CANCELLED, now()), memberId);
        }
        ProviderResult result;
        try {
            result = provider.cancel(v.getProviderTransactionId(), v.getVerificationId());
        } catch (BusinessException e) {
            if (!isUnconfirmed(e)) throw e;
            auditLog.providerUnavailable(v.getVerificationId(), "CANCEL");
            // 사용자의 취소 의사는 서비스 기준으로 확정한다. 이후 외부에서 뒤늦게 성공이 와도 반영하지 않는다
            return toResponse(txService.finish(v.getId(), VerificationAttemptStatus.CANCELLED,
                    VerificationEndReason.USER_CANCELLED_PROVIDER_UNCONFIRMED, now()), memberId);
        }
        // 외부에서 취소보다 성공이 먼저 확정됐다면 성공이 반영된다 (외부 기관 기준)
        return toResponse(txService.applyProviderResult(v, result, now()), memberId);
    }

    // ── 내부 ─────────────────────────────────────────────────────

    private IdentityVerification findOwned(Long memberId, String verificationId) {
        return verificationRepository.findByVerificationIdAndMemberId(verificationId, memberId)
                .orElseThrow(() -> new BusinessException(IdentityVerificationErrorCode.IDENTITY_VERIFICATION_NOT_FOUND));
    }

    /** PENDING 요청을 외부 결과와 맞춘다. 외부 장애면 503 (유예시간이 지났으면 만료 확정) */
    private IdentityVerification sync(IdentityVerification v) {
        if (!v.isPending()) {
            return v;   // 종료된 요청은 외부를 다시 부르지 않는다 → 반복 조회해도 중복 반영 없음
        }
        LocalDateTime now = now();
        if (!v.isRegisteredToProvider()) {
            if (v.isExpiredAt(now)) {
                return txService.finish(v.getId(), VerificationAttemptStatus.EXPIRED,
                        VerificationEndReason.REGISTRATION_UNCONFIRMED, now);
            }
            return v;   // 아직 외부 등록 전(응답 유실) — 생성 API 재호출 시 재등록
        }
        ProviderResult result;
        try {
            result = provider.fetchResult(v.getProviderTransactionId(), v.getVerificationId());
        } catch (BusinessException e) {
            if (!isUnconfirmed(e)) throw e;
            auditLog.providerUnavailable(v.getVerificationId(), "FETCH_RESULT");
            if (now.isAfter(v.getExpiresAt().plus(properties.unconfirmedGrace()))) {
                return txService.finish(v.getId(), VerificationAttemptStatus.EXPIRED,
                        VerificationEndReason.UNCONFIRMED_AFTER_EXPIRY, now);
            }
            throw e;   // 상태는 그대로(PENDING). 클라이언트는 잠시 후 재조회 (503 또는 서명 검증 실패 502)
        }
        IdentityVerification applied = txService.applyProviderResult(v, result, now);
        if (result.outcome() == ProviderOutcome.PENDING && !applied.isPending()) {
            cancelAtProviderQuietly(applied);   // 서비스 기준 만료 → 외부 요청도 정리 (실패해도 무방)
        }
        return applied;
    }

    /** 생성 API 안에서의 동기화: 외부 장애여도 진행 중 요청을 그대로 이어가게 한다 */
    private IdentityVerification syncQuietly(IdentityVerification v) {
        try {
            return sync(v);
        } catch (BusinessException e) {
            if (!isUnconfirmed(e)) throw e;
            return v;
        }
    }

    /** 외부 기관 등록. 실패하면 요청은 PENDING(미등록)으로 남고 503을 응답한다 */
    private IdentityVerification ensureRegistered(IdentityVerification v) {
        if (v.isRegisteredToProvider() || !v.isPending()) {
            return v;
        }
        ProviderTransaction tx;
        try {
            tx = provider.register(v.getVerificationId(), properties.callbackUrl());
        } catch (BusinessException e) {
            if (isUnconfirmed(e)) auditLog.providerUnavailable(v.getVerificationId(), "REGISTER");
            throw e;
        }
        auditLog.registered(v.getVerificationId(), tx.transactionId());
        return txService.attachProviderTransaction(v.getId(), tx.transactionId(), tx.authUrl());
    }

    private void cancelAtProviderQuietly(IdentityVerification v) {
        try {
            provider.cancel(v.getProviderTransactionId(), v.getVerificationId());
        } catch (Exception e) {
            log.info("[본인확인] 만료 후 외부 취소 정리 실패(무시) verificationId={}", v.getVerificationId());
        }
    }

    /** 외부 결과의 "의미를 확정할 수 없는" 실패: 연결 실패·시간 초과·5xx·서명 검증 실패 */
    private static boolean isUnconfirmed(BusinessException e) {
        return e.getErrorCode() == IdentityVerificationErrorCode.IDENTITY_PROVIDER_UNAVAILABLE
                || e.getErrorCode() == IdentityVerificationErrorCode.IDENTITY_RESULT_INTEGRITY_FAILED;
    }

    private IdentityVerificationResponse toResponse(IdentityVerification v, Long memberId) {
        return IdentityVerificationResponse.of(v, memberService.findById(memberId), properties.pollInterval().toSeconds());
    }

    // DB(TIMESTAMP(6))와 같은 마이크로초 단위로 자른다. Linux 시계는 나노초까지 주므로,
    // 자르지 않으면 첫 응답과 이후 DB에서 읽은 값의 시각이 달라진다 (CI에서 발견)
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
