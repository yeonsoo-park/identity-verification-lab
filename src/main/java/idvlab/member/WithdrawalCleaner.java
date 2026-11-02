package idvlab.member;

// 탈퇴하는 회원의 도메인 데이터를 정리하는 확장 지점. 탈퇴 트랜잭션 안에서 호출된다
public interface WithdrawalCleaner {
    void clean(Long memberId);
}
