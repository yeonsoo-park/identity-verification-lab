package idvlab.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// 테스트 로그인 허용 키 목록 (application.yml: lab.test-users)
@ConfigurationProperties(prefix = "lab")
public record LabProperties(List<String> testUsers) {
}
