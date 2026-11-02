package idvlab.member;

import idvlab.common.SuccessResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController implements MemberApiDocs {

    private final MemberService memberService;

    @GetMapping("/me")
    public ResponseEntity<SuccessResponse<MemberResponse>> getMyInfo(@AuthenticationPrincipal Long memberId) {
        return ResponseEntity.ok(SuccessResponse.of(MemberSuccessCode.MEMBER_INFO_FETCHED, memberService.getMyInfo(memberId)));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Long memberId) {
        memberService.withdraw(memberId);
        return ResponseEntity.noContent().build();
    }
}
