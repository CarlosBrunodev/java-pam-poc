package br.com.vli.pam.client;

import br.com.vli.pam.config.DelineaProperties;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Cliente mínimo do Delinea Secret Server:
 * 1. POST /oauth2/token (grant_type=password) para obter o bearer token;
 * 2. GET /api/v1/secrets/{id}/fields/{slug} com Authorization: Bearer.
 * O token fica em cache até perto de expirar.
 */
@Component
public class DelineaClient {

    private static final Logger log = LoggerFactory.getLogger(DelineaClient.class);

    // Renova o token um pouco antes de expirar para não usar um token vencido em voo
    private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final DelineaProperties properties;
    private final RestClient restClient;

    private String cachedToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public DelineaClient(DelineaProperties properties) {
        this.properties = properties;

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public String getSecretField(int secretId, String fieldSlug) {
        try {
            return fetchField(secretId, fieldSlug, getToken());
        } catch (HttpClientErrorException.Unauthorized e) {
            // Token pode ter sido revogado no servidor antes do expires_in; tenta uma única vez com token novo
            log.warn("401 ao ler secret {}; renovando token e tentando novamente", secretId);
            invalidateToken();
            return fetchField(secretId, fieldSlug, getToken());
        }
    }

    private String fetchField(int secretId, String fieldSlug, String token) {
        String raw = restClient.get()
                .uri("/api/v1/secrets/{id}/fields/{slug}", secretId, fieldSlug)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        return unquoteJsonString(raw);
    }

    private synchronized String getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt.minus(TOKEN_EXPIRY_MARGIN))) {
            return cachedToken;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("username", properties.username());
        form.add("password", properties.password());

        TokenResponse response = restClient.post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Delinea retornou resposta de token vazia");
        }

        cachedToken = response.accessToken();
        tokenExpiresAt = Instant.now().plusSeconds(response.expiresIn());
        log.info("Token obtido no Delinea ({})", response);
        return cachedToken;
    }

    private synchronized void invalidateToken() {
        cachedToken = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    /** O endpoint /fields/{slug} devolve o valor como string JSON ("valor"); remove as aspas se presentes. */
    private static String unquoteJsonString(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return trimmed;
    }

    /** Usado pelo controller para expor o status HTTP do erro de forma legível. */
    public static String describe(Exception e) {
        if (e instanceof org.springframework.web.client.RestClientResponseException re) {
            return re.getStatusCode() + " - " + re.getResponseBodyAsString();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
