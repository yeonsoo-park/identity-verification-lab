package idvlab.auth;

import idvlab.common.SuccessResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class TestLoginController implements TestLoginApiDocs {

    private final TestLoginService testLoginService;

    @PostMapping("/test-login")
    public ResponseEntity<SuccessResponse<AuthResponse>> testLogin(@Valid @RequestBody TestLoginRequest request) {
        return ResponseEntity.ok(SuccessResponse.of(AuthCode.Success.TEST_LOGIN_SUCCESS, testLoginService.login(request.testUserKey())));
    }
}
