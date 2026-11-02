package idvlab.member;

import idvlab.common.BusinessException;
import idvlab.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final MemberCreator memberCreator;
    private final List<WithdrawalCleaner> cleaners;

    public record FindOrJoinResult(Member member, boolean isNew) {}

    public FindOrJoinResult findOrJoin(String loginKey, String name, String email) {
        return memberRepository.findByLoginKey(loginKey)
                .map(m -> new FindOrJoinResult(m, false))
                .orElseGet(() -> {
                    try {
                        return new FindOrJoinResult(memberCreator.create(loginKey, name, email), true);
                    } catch (DataIntegrityViolationException e) {
                        return new FindOrJoinResult(memberRepository.findByLoginKey(loginKey)
                                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR)), false);
                    }
                });
    }

    @Transactional(readOnly = true)
    public Member findById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public MemberResponse getMyInfo(Long memberId) {
        return MemberResponse.from(findById(memberId));
    }

    /** 회원 잠금 → 도메인 정리(진행 중 본인확인 취소 등) → 개인정보·본인확인 상태 파기. 중복 호출은 무시 */
    @Transactional
    public void withdraw(Long memberId) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        if (!member.isActive()) {
            return;
        }
        cleaners.forEach(c -> c.clean(memberId));
        member.withdraw(LocalDateTime.now());
    }
}
