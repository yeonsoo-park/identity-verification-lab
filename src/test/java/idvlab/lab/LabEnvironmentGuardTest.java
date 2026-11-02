package idvlab.lab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabEnvironmentGuardTest {

    private final LabEnvironmentGuard guard = new LabEnvironmentGuard();

    private MockEnvironment env(String dbUrl, String mockUrl) {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", dbUrl)
                .withProperty("lab.mock-identity-provider.base-url", mockUrl);
    }

    @Test
    @DisplayName("로컬 접속지면 통과")
    void allowsLocal() {
        assertThatCode(() -> guard.postProcessEnvironment(
                env("jdbc:postgresql://localhost:5434/idvlab", "http://localhost:18090"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DB가 원격이면 연결 전에 기동 중단")
    void rejectsRemoteDb() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                env("jdbc:postgresql://prod-db.example.com:5432/app", "http://localhost:18090"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url");
    }

    @Test
    @DisplayName("외부 기관 주소가 원격이면 기동 중단 (실제 기관 호출 방지)")
    void rejectsRemoteProvider() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                env("jdbc:postgresql://localhost:5434/idvlab", "https://api.example.com"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mock-identity-provider");
    }

    @Test
    @DisplayName("localhost를 흉내 낸 원격 호스트도 거부")
    void rejectsLookalikeHost() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                env("jdbc:postgresql://localhost.evil.com:5432/x", "http://localhost:18090"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hostOf() {
        assertThat(LabEnvironmentGuard.hostOf("jdbc:postgresql://127.0.0.1:5434/db")).isEqualTo("127.0.0.1");
        assertThat(LabEnvironmentGuard.hostOf("http://[::1]:18090/x")).isEqualTo("[::1]");
        assertThat(LabEnvironmentGuard.hostOf("localhost")).isEqualTo("localhost");
    }
}
