package com.fincore.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Component
public class JwtAuthenticationFilter implements GatewayFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Value("${jwt.public-key}")
    private Resource publicKeyResource;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String authHeader = exchange.getRequest()
                .getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing Authorization header");
        }

        String token = authHeader.substring(7);
        return validateToken(token, exchange, chain);
    }

    private Mono<Void> validateToken(String token,
                                     ServerWebExchange exchange,
                                     GatewayFilterChain chain) {
        return Mono.fromCallable(() -> parseToken(token))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("Token validation error in filter: ", e);
                    return unauthorized(exchange, "Invalid or expired token").then(Mono.empty());
                })
                .flatMap(claims -> {
                    HttpHeaders newHeaders = new HttpHeaders();
                    newHeaders.putAll(exchange.getRequest().getHeaders());
                    newHeaders.set("X-User-Id", claims.getSubject());
                    newHeaders.set("X-User-Role", claims.get("role", String.class));
                    newHeaders.set("X-User-Email", claims.get("email", String.class));
                    HttpHeaders readOnlyNewHeaders = HttpHeaders.readOnlyHttpHeaders(newHeaders);

                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public HttpHeaders getHeaders() {
                            return readOnlyNewHeaders;
                        }
                    };

                    log.info("JWT validated userId={} role={}",
                            claims.getSubject(),
                            claims.get("role", String.class));

                    return chain.filter(exchange.mutate()
                            .request(mutatedRequest)
                            .build());
                });
    }

    private Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(loadPublicKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Throwable e) {
            log.error("Token validation failed: ", e);
            throw new RuntimeException("Invalid token: " + e.getMessage(), e);
        }
    }

    private volatile PublicKey cachedPublicKey;

    private PublicKey loadPublicKey() {
        if (cachedPublicKey == null) {
            synchronized (this) {
                if (cachedPublicKey == null) {
                    try {
                        String key = new String(publicKeyResource.getInputStream().readAllBytes())
                                .replace("-----BEGIN PUBLIC KEY-----", "")
                                .replace("-----END PUBLIC KEY-----", "")
                                .replaceAll("\\s", "");
                        byte[] decoded = Base64.getDecoder().decode(key);
                        cachedPublicKey = KeyFactory.getInstance("RSA")
                                .generatePublic(new X509EncodedKeySpec(decoded));
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to load public key", e);
                    }
                }
            }
        }
        return cachedPublicKey;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders()
                .setContentType(MediaType.APPLICATION_JSON);

        String body = """
            {"status":401,"error":"UNAUTHORIZED","message":"%s"}
            """.formatted(message);

        var buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1;
    }
}