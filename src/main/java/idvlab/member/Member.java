package idvlab.member;

import idvlab.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 최소 회원 모델. 실제 서비스의 소셜 로그인 회원을 대신해 "테스트 로그인 키"로 식별한다.
 * 본인확인 결과로 이름·이메일을 덮어쓰지 않는다 (본인확인 기관에서 그런 값을 받지도 않는다).
 */
@Entity
@Table(name = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String loginKey;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 120)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityVerificationStatus identityVerificationStatus;

    private LocalDateTime identityVerifiedAt;

    private LocalDateTime withdrawnAt;

    public static Member join(String loginKey, String name, String email) {
        Member m = new Member();
        m.loginKey = loginKey;
        m.name = name;
        m.email = email;
        m.status = MemberStatus.ACTIVE;
        m.identityVerificationStatus = IdentityVerificationStatus.UNVERIFIED;
        return m;
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    public boolean isIdentityVerified() {
        return identityVerificationStatus == IdentityVerificationStatus.VERIFIED;
    }

    /** 본인확인 완료 반영. 이미 확인 완료면 시각을 바꾸지 않고 false (반복 조회에도 중복 반영 없음) */
    public boolean markIdentityVerified(LocalDateTime verifiedAt) {
        if (isIdentityVerified()) {
            return false;
        }
        this.identityVerificationStatus = IdentityVerificationStatus.VERIFIED;
        this.identityVerifiedAt = verifiedAt;
        return true;
    }

    /** 탈퇴: 개인정보와 본인확인 상태를 파기하고, 같은 로그인 키로 새로 가입할 수 있게 키를 분리한다 */
    public void withdraw(LocalDateTime now) {
        if (!isActive()) {
            return;
        }
        this.status = MemberStatus.WITHDRAWN;
        this.loginKey = "WITHDRAWN_" + UUID.randomUUID();
        this.name = "탈퇴한 회원";
        this.email = "withdrawn+" + UUID.randomUUID() + "@deleted.invalid";
        this.identityVerificationStatus = IdentityVerificationStatus.UNVERIFIED;
        this.identityVerifiedAt = null;
        this.withdrawnAt = now;
    }
}
