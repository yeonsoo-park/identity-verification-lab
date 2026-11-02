package idvlab.lab;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// 최소 데모 화면 (가입 직후 안내 → 마이페이지 → 모의 인증 → 결과)
@Hidden
@RestController
public class LabDemoController {

    @GetMapping(value = "/lab/identity-demo", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> demo() throws IOException {
        String html = new ClassPathResource("lab/identity-demo.html").getContentAsString(StandardCharsets.UTF_8);
        return ResponseEntity.ok(html);
    }
}
