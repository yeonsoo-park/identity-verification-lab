package idvlab.auth;

import idvlab.common.BusinessException;
import idvlab.member.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 실습용 테스트 회원 로그인. 실제 서비스의 소셜 로그인(카카오·애플 등)을 대신한다.
 * 실제 서비스라면 이 자리에 소셜 로그인 → 회원 조회·생성 → 토큰 발급이 들어가며, 본인확인 흐름은 바뀌지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TestLoginService {

    private final LabProperties labProperties;
    private final MemberService memberService;
    private final JwtProvider jwtProvider;

    public AuthResponse login(String testUserKey) {
        if (labProperties.testUsers() == null || !labProperties.testUsers().contains(testUserKey)) {
            throw new BusinessException(AuthCode.Error.TEST_USER_NOT_ALLOWED);
        }
        MemberService.FindOrJoinResult result = memberService.findOrJoin(
                testUserKey, "실습회원-" + testUserKey, testUserKey + "@lab.invalid");
        return new AuthResponse(jwtProvider.createAccessToken(result.member().getId()),
                result.member().getName(), result.isNew());
    }
}
