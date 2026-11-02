package idvlab.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import idvlab.verification.entity.IdentityProviderType;
import idvlab.verification.provider.IdentityVerificationProvider;
import idvlab.verification.provider.ProviderOutcome;
import idvlab.verification.provider.ProviderResult;
import idvlab.verification.provider.ProviderTransaction;
import idvlab.verification.IdentityVerificationErrorCode;
import idvlab.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 서버 내부 정책 통합 테스트 (test 프로필, 로컬 테스트 DB).
 * 외부 기관은 시나리오를 자유롭게 만들 수 있는 Fake로, 시계는 앞으로 돌릴 수 있는 시계로 바꿔
 * 실제 모의 서버로 재현하기 어려운 시간·경합 상황을 검증한다.
 * (실제 HTTP 왕복은 lab/e2e/run_e2e.py에서 검증)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdentityVerificationIntegrationTest {

    private static final String BASE = "/api/v1/members/me/identity-verifications";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FakeProvider provider;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    // ── 테스트 대역 ──────────────────────────────────────────────
    static class FakeProvider implements IdentityVerificationProvider {
        final Map<String, ProviderResult> results = new ConcurrentHashMap<>();
        final AtomicBoolean down = new AtomicBoolean(false);

        @Override public IdentityProviderType type() { return IdentityProviderType.MOCK; }

        @Override public ProviderTransaction register(String clientRequestId, String callbackUrl) {
            failIfDown();
            String tx = "fake-" + clientRequestId;
            results.putIfAbsent(tx, new ProviderResult(ProviderOutcome.PENDING, "MV0100", null));
            return new ProviderTransaction(tx, "http://localhost:18090/fake?tx=" + tx, null);
        }

        @Override public ProviderResult fetchResult(String tx, String clientRequestId) {
            failIfDown();
            return results.getOrDefault(tx, new ProviderResult(ProviderOutcome.NOT_FOUND, "MV9106", null));
        }

        @Override public synchronized ProviderResult cancel(String tx, String clientRequestId) {
            failIfDown();
            ProviderResult current = results.get(tx);
            if (current != null && current.outcome() == ProviderOutcome.PENDING) {
                results.put(tx, new ProviderResult(ProviderOutcome.CLIENT_CANCELLED, "MV1004", LocalDateTime.now()));
            }
            return results.get(tx);
        }

        void complete(String verificationId, ProviderOutcome outcome, LocalDateTime at) {
            results.put("fake-" + verificationId, new ProviderResult(outcome, "TEST", at));
        }

        private void failIfDown() {
            if (down.get()) throw new BusinessException(IdentityVerificationErrorCode.IDENTITY_PROVIDER_UNAVAILABLE);
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant now = Instant.now();
        void advance(Duration d) { now = now.plus(d); }
        void set(Instant instant) { now = instant; }
        void reset() { now = Instant.now(); }
        @Override public ZoneId getZone() { return ZoneId.systemDefault(); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class Doubles {
        @Bean @Primary FakeProvider fakeProvider() { return new FakeProvider(); }
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }

    // ── 공통 ─────────────────────────────────────────────────────
    @BeforeEach
    void setUp() {
        provider.down.set(false);
        provider.results.clear();
        clock.reset();
        // 테스트 회원을 매번 새 계정처럼: 기존 LOCAL 회원의 인증 이력과 상태를 정리
        jdbc.update("delete from identity_verifications");
        jdbc.update("update members set identity_verification_status='UNVERIFIED', identity_verified_at=null");
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private String login(String key) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/test-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"testUserKey\":\"" + key + "\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return "Bearer " + json(r).at("/data/accessToken").asText();
    }

    private JsonNode start(String token) throws Exception {
        return json(mockMvc.perform(post(BASE).header("Authorization", token)).andReturn());
    }

    private MvcResult getStatus(String token, String id) throws Exception {
        return mockMvc.perform(get(BASE + "/" + id).header("Authorization", token)).andReturn();
    }

    private JsonNode cancel(String token, String id) throws Exception {
        return json(mockMvc.perform(post(BASE + "/" + id + "/cancel").header("Authorization", token)).andReturn());
    }

    private JsonNode me(String token) throws Exception {
        return json(mockMvc.perform(get("/api/v1/members/me").header("Authorization", token)).andReturn()).get("data");
    }

    // ── 테스트 ───────────────────────────────────────────────────
    @Test
    @DisplayName("내 정보 조회: 회원 정보 + 본인확인 상태 필드")
    void memberInfoRegression() throws Exception {
        JsonNode me = me(login("lab-user-a"));
        assertThat(me.has("name") && me.has("email")).isTrue();
        assertThat(me.get("identityVerificationStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(me.get("identityVerifiedAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("클라이언트가 성공이라고 주장해도 외부 결과가 PENDING이면 반영되지 않는다")
    void clientCannotClaimSuccess() throws Exception {
        String t = login("lab-user-a");
        String id = start(t).at("/data/verificationId").asText();
        // 클라이언트가 status=SUCCEEDED 같은 값을 보내도 API는 받지 않는다 — 서버 조회 결과만 사용
        MvcResult r = mockMvc.perform(get(BASE + "/" + id + "?status=SUCCEEDED").header("Authorization", t)).andReturn();
        assertThat(json(r).at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(me(t).get("identityVerificationStatus").asText()).isEqualTo("UNVERIFIED");
    }

    @Test
    @DisplayName("서비스 유효시간이 지나도록 외부가 PENDING이면 EXPIRED(EXPIRED_BEFORE_COMPLETION)")
    void expiresOnServiceSide() throws Exception {
        String t = login("lab-user-a");
        String id = start(t).at("/data/verificationId").asText();
        clock.advance(Duration.ofMinutes(6));
        JsonNode d = json(getStatus(t, id)).get("data");
        assertThat(d.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(d.get("endReason").asText()).isEqualTo("EXPIRED_BEFORE_COMPLETION");
        assertThat(d.get("accountStatus").asText()).isEqualTo("UNVERIFIED");
    }

    @Test
    @DisplayName("외부 성공 시각이 서비스 유효시간 이후면 성공으로 인정하지 않는다")
    void successAfterExpiryIsNotAccepted() throws Exception {
        String t = login("lab-user-a");
        JsonNode s = start(t).get("data");
        String id = s.get("verificationId").asText();
        LocalDateTime late = LocalDateTime.parse(s.get("expiresAt").asText()).plusSeconds(30);
        provider.complete(id, ProviderOutcome.SUCCEEDED, late);
        clock.advance(Duration.ofMinutes(6));
        JsonNode d = json(getStatus(t, id)).get("data");
        assertThat(d.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(d.get("endReason").asText()).isEqualTo("SUCCEEDED_AFTER_EXPIRY");
        assertThat(me(t).get("identityVerificationStatus").asText()).isEqualTo("UNVERIFIED");
    }

    @Test
    @DisplayName("외부 장애: 유예 전에는 503 + PENDING 유지, 유예 후에는 EXPIRED(UNCONFIRMED_AFTER_EXPIRY)")
    void outageBeforeAndAfterGrace() throws Exception {
        String t = login("lab-user-a");
        String id = start(t).at("/data/verificationId").asText();
        provider.down.set(true);

        MvcResult r = getStatus(t, id);
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(jdbc.queryForObject("select status from identity_verifications where verification_id=?", String.class, id))
                .isEqualTo("PENDING");

        clock.advance(Duration.ofMinutes(5 + 10 + 1));   // 유효시간 5분 + 유예 10분 경과
        JsonNode d = json(getStatus(t, id)).get("data");
        assertThat(d.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(d.get("endReason").asText()).isEqualTo("UNCONFIRMED_AFTER_EXPIRY");
    }

    @Test
    @DisplayName("외부 등록 응답을 못 받은 채 유효시간이 지나면 EXPIRED(REGISTRATION_UNCONFIRMED), 다음 요청은 새로 생성")
    void registrationUnconfirmed() throws Exception {
        String t = login("lab-user-a");
        provider.down.set(true);
        MvcResult first = mockMvc.perform(post(BASE).header("Authorization", t)).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(503);
        String id = jdbc.queryForObject("select v.verification_id from identity_verifications v join members m on m.id=v.member_id " +
                "where m.login_key='lab-user-a' and v.status='PENDING'", String.class);

        clock.advance(Duration.ofMinutes(6));
        provider.down.set(false);
        JsonNode d = json(getStatus(t, id)).get("data");
        assertThat(d.get("endReason").asText()).isEqualTo("REGISTRATION_UNCONFIRMED");

        MvcResult again = mockMvc.perform(post(BASE).header("Authorization", t)).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("외부 장애 중 명시 취소 → 서비스 기준 CANCELLED, 이후 외부 성공은 반영되지 않음")
    void cancelWhileProviderDown() throws Exception {
        String t = login("lab-user-a");
        String id = start(t).at("/data/verificationId").asText();
        provider.down.set(true);
        JsonNode c = cancel(t, id).get("data");
        assertThat(c.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(c.get("endReason").asText()).isEqualTo("USER_CANCELLED_PROVIDER_UNCONFIRMED");

        provider.down.set(false);
        provider.complete(id, ProviderOutcome.SUCCEEDED, LocalDateTime.now(clock));
        assertThat(json(getStatus(t, id)).at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(me(t).get("identityVerificationStatus").asText()).isEqualTo("UNVERIFIED");
    }

    @Test
    @DisplayName("취소보다 외부 성공이 먼저 확정됐다면 취소 응답은 SUCCEEDED (외부 기관 기준)")
    void successBeatsCancel() throws Exception {
        String t = login("lab-user-a");
        String id = start(t).at("/data/verificationId").asText();
        provider.complete(id, ProviderOutcome.SUCCEEDED, LocalDateTime.now(clock));
        JsonNode c = cancel(t, id).get("data");
        assertThat(c.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(c.get("accountStatus").asText()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("성공 조회·취소·만료 판단이 동시에 와도 종료 상태는 하나, 계정 반영은 1회")
    void concurrentFinalization() throws Exception {
        String t = login("lab-user-a");
        String id = start(t).at("/data/verificationId").asText();
        provider.complete(id, ProviderOutcome.SUCCEEDED, LocalDateTime.now(clock));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            boolean isCancel = i % 2 == 0;
            futures.add(pool.submit(() -> {
                go.await();
                return isCancel
                        ? mockMvc.perform(post(BASE + "/" + id + "/cancel").header("Authorization", t)).andReturn().getResponse().getStatus()
                        : getStatus(t, id).getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> f : futures) assertThat(f.get()).isEqualTo(200);
        pool.shutdown();

        String status = jdbc.queryForObject("select status from identity_verifications where verification_id=?", String.class, id);
        assertThat(status).isEqualTo("SUCCEEDED");
        JsonNode me = me(t);
        assertThat(me.get("identityVerificationStatus").asText()).isEqualTo("VERIFIED");
        String verifiedAt = me.get("identityVerifiedAt").asText();
        getStatus(t, id);
        assertThat(me(t).get("identityVerifiedAt").asText()).isEqualTo(verifiedAt);
    }

    @Test
    @DisplayName("성공 후 앱으로 복귀하지 못했어도, 다시 '본인확인하기'를 누르면 성공이 반영되고 409")
    void successWithoutReturnIsRecoveredOnNextStart() throws Exception {
        String t = login("lab-user-a");
        String id = start(t).at("/data/verificationId").asText();
        provider.complete(id, ProviderOutcome.SUCCEEDED, LocalDateTime.now(clock));   // 상태 조회 없이 앱 이탈

        MvcResult again = mockMvc.perform(post(BASE).header("Authorization", t)).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(again).get("code").asText()).isEqualTo("IDENTITY_ALREADY_VERIFIED");
        assertThat(me(t).get("identityVerificationStatus").asText()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("시계가 나노초 단위여도 응답 시각과 저장된 시각이 같다 (취소 재호출 시 endedAt 동일)")
    void timestampsMatchDatabasePrecision() throws Exception {
        clock.set(Instant.now().plusNanos(123));   // Linux처럼 나노초가 있는 시계를 재현
        String t = login("lab-user-a");
        String id = start(t).at("/data/verificationId").asText();
        JsonNode first = cancel(t, id).get("data");
        JsonNode again = cancel(t, id).get("data");
        assertThat(again.get("endedAt").asText()).isEqualTo(first.get("endedAt").asText());
        assertThat(again.get("expiresAt").asText()).isEqualTo(first.get("expiresAt").asText());
    }

    @Test
    @DisplayName("다른 회원 요청 조회·취소는 404, 원래 요청은 그대로")
    void otherMemberBlocked() throws Exception {
        String a = login("lab-user-a");
        String b = login("lab-user-b");
        String id = start(a).at("/data/verificationId").asText();
        assertThat(getStatus(b, id).getResponse().getStatus()).isEqualTo(404);
        assertThat(cancel(b, id).get("code").asText()).isEqualTo("IDENTITY_VERIFICATION_NOT_FOUND");
        assertThat(json(getStatus(a, id)).at("/data/status").asText()).isEqualTo("PENDING");
    }
}
