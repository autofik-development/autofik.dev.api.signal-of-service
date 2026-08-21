package com.autofik.sos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the acting identity from the headers APISIX writes, and nothing else — except for the
 * temporary fallback below.
 *
 * <p>The caller must never be able to name themselves. Every controller reads the principal this
 * filter installs instead of a {@code userId} / {@code mechanicId} parameter, because a parameter
 * is chosen by whoever sends the request.
 *
 * <p>Exactly one of the two headers must be present. Both means the edge is misconfigured and the
 * request is rejected rather than guessed at — neither identity is more trustworthy than the other.
 *
 * <p>This is only as strong as the network policy that keeps anything but APISIX off the gateway:
 * the headers carry no signature of their own.
 *
 * <p>ponytail: the internal gateway isn't forwarding these headers yet, so nothing reaches this
 * service unauthenticated-401 without them. Until it does, a request with neither header falls
 * back to a {@code userId}/{@code mechanicId} the caller already supplied — a field in a JSON
 * body for POST/PATCH/PUT, or a query parameter for everything else (GET/DELETE, or a body that
 * doesn't carry one). This reopens exactly the impersonation hole the header contract exists to
 * close: the caller is choosing their own identity again. Delete the whole fallback block the
 * moment the gateway forwards real headers.
 */
@Component
public class GatewayIdentityFilter extends OncePerRequestFilter {

    public static final String USER_HEADER = "X-User-Id";
    public static final String MECHANIC_HEADER = "X-Mechanic-Id";
    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_MECHANIC = "ROLE_MECHANIC";

    private static final Set<String> BODY_READABLE_METHODS = Set.of("POST", "PATCH", "PUT");

    private final ObjectMapper objectMapper;

    public GatewayIdentityFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String user = request.getHeader(USER_HEADER);
        String mechanic = request.getHeader(MECHANIC_HEADER);
        HttpServletRequest downstream = request;

        if (user == null && mechanic == null) {
            String contentType = request.getContentType();
            if (contentType != null && contentType.startsWith("application/json")
                    && BODY_READABLE_METHODS.contains(request.getMethod())) {
                CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
                downstream = cached;
                String[] fromBody = idFieldsFromJson(cached.cachedBody());
                user = fromBody[0];
                mechanic = fromBody[1];
            }
            if (user == null && mechanic == null) {
                // Covers every request the JSON-body check above doesn't: GET/DELETE, and any
                // POST/PATCH/PUT whose body carries neither field. Testers add ?userId=... or
                // ?mechanicId=... by hand.
                user = request.getParameter("userId");
                mechanic = request.getParameter("mechanicId");
            }
        }

        // Exactly one, or the request stays unauthenticated and the filter chain answers 401.
        if ((user == null) != (mechanic == null)) {
            try {
                UUID id = UUID.fromString(user != null ? user : mechanic);
                String role = user != null ? ROLE_USER : ROLE_MECHANIC;
                // The bearer goes in the credentials slot: SSO's profile and vehicle endpoints are
                // scoped to the calling user, so the projection reads need the caller's own token
                // and there is no service-to-service equivalent. Never log this value.
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        id, request.getHeader(HttpHeaders.AUTHORIZATION),
                        List.of(new SimpleGrantedAuthority(role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (IllegalArgumentException malformedUuid) {
                // Leave the context empty — a header we cannot parse is a header we do not trust.
            }
        }

        chain.doFilter(downstream, response);
    }

    private String[] idFieldsFromJson(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            String userId = node.hasNonNull("userId") ? node.get("userId").asText() : null;
            String mechanicId = node.hasNonNull("mechanicId") ? node.get("mechanicId").asText() : null;
            return new String[]{userId, mechanicId};
        } catch (Exception malformedOrEmptyBody) {
            return new String[]{null, null};
        }
    }

    /**
     * Buffers the whole body up front so it can be read here (to sniff {@code userId}/
     * {@code mechanicId}) and still be read again downstream by {@code @RequestBody} binding —
     * a plain {@code getInputStream()} read is one-shot and would otherwise leave the controller
     * with nothing.
     */
    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        byte[] cachedBody() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return buffer.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
