package idvlab.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.List;

/**
 * 실습 안전장치: DB·모의 기관 접속지가 로컬이 아니면 "연결 전에" 기동을 막는다.
 * (EnvironmentPostProcessor는 DataSource·Flyway보다 먼저 실행된다. META-INF/spring.factories에 등록)
 */
public class LabEnvironmentGuard implements EnvironmentPostProcessor {

    private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "[::1]");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        requireLocal("spring.datasource.url", env.getProperty("spring.datasource.url"));
        requireLocal("lab.mock-identity-provider.base-url", env.getProperty("lab.mock-identity-provider.base-url"));
    }

    private void requireLocal(String key, String value) {
        if (value == null || !LOCAL_HOSTS.contains(hostOf(value))) {
            throw new IllegalStateException("[LAB GUARD] " + key + " 는 로컬 주소여야 합니다. 현재 값: " + value);
        }
    }

    // "jdbc:postgresql://localhost:5434/db", "http://localhost:18090", "localhost" → "localhost"
    static String hostOf(String value) {
        String rest = value.contains("//") ? value.substring(value.indexOf("//") + 2) : value;
        int end = rest.length();
        for (char c : new char[]{'/', '?'}) {
            int i = rest.indexOf(c);
            if (i >= 0) end = Math.min(end, i);
        }
        String hostPort = rest.substring(0, end);
        if (hostPort.startsWith("[")) {
            return hostPort.substring(0, hostPort.indexOf(']') + 1);
        }
        int colon = hostPort.indexOf(':');
        return colon >= 0 ? hostPort.substring(0, colon) : hostPort;
    }
}
