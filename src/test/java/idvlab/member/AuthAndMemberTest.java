package idvlab.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import idvlab.auth.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 최소 회원·인증 기반이 본인확인 정책의 전제(토큰으로만 회원 식별, 탈퇴 토큰 차단)를 지키는지 검증 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAndMemberTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtProvider jwtProvider;

    private String login(String key) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/test-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"testUserKey\":\"" + key + "\"}")).andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).at("/data/accessToken").asText();
    }

    @Test
    @DisplayName("토큰 없이·위조 토큰으로 보호 API 호출 → 401 공통 오류 형식")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/members/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/members/me").header("Authorization", "Bearer forged.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("허용되지 않은 테스트 키 → 400, 키 누락 → 400")
    void testLoginValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/test-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"testUserKey\":\"someone\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEST_USER_NOT_ALLOWED"));
        mockMvc.perform(post("/api/v1/auth/test-login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    @DisplayName("같은 회원·같은 초에 발급한 토큰도 서로 다르다 (jti)")
    void tokensAreUnique() {
        assertThat(jwtProvider.createAccessToken(1L)).isNotEqualTo(jwtProvider.createAccessToken(1L));
    }

    @Test
    @DisplayName("탈퇴: 204 → 같은 토큰 401 → 같은 키 재로그인은 새 계정(isNewUser=true, 미확인)")
    void withdrawal() throws Exception {
        String token = login("lab-user-c");
        mockMvc.perform(delete("/api/v1/members/me").header("Authorization", token)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/members/me").header("Authorization", token)).andExpect(status().isUnauthorized());

        String body = mockMvc.perform(post("/api/v1/auth/test-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"testUserKey\":\"lab-user-c\"}")).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).at("/data/isNewUser").asBoolean()).isTrue();
        String newToken = "Bearer " + objectMapper.readTree(body).at("/data/accessToken").asText();
        mockMvc.perform(get("/api/v1/members/me").header("Authorization", newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identityVerificationStatus").value("UNVERIFIED"));
    }

    @Test
    @DisplayName("없는 경로는 404 공통 오류 형식")
    void unknownEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/nope").header("Authorization", login("lab-user-a")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVALID_ENDPOINT"));
    }
}
