package idvlab.member;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 신규 가입 INSERT만 독립 트랜잭션으로 — 동시 첫 로그인으로 유니크 제약이 깨져도 호출 측이 재조회로 복구
@Component
@RequiredArgsConstructor
public class MemberCreator {

    private final MemberRepository memberRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Member create(String loginKey, String name, String email) {
        return memberRepository.saveAndFlush(Member.join(loginKey, name, email));
    }
}
