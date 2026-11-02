package idvlab.auth;

import idvlab.member.MemberRepository;
import idvlab.member.MemberStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer 토큰 → 회원 ID를 principal로 설정. 탈퇴한 회원의 토큰은 인증하지 않는다(DB 확인).
 * 컨트롤러는 @AuthenticationPrincipal Long 으로만 회원을 식별하고, 요청 Body/Path의 회원 ID를 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final MemberRepository memberRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwtProvider.parseMemberId(header.substring(7).trim())
                    .filter(id -> memberRepository.existsByIdAndStatus(id, MemberStatus.ACTIVE))
                    .ifPresent(id -> SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(id, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        }
        chain.doFilter(request, response);
    }
}
