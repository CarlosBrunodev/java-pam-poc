package br.com.vli.pam.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuração de acesso ao Delinea Secret Server.
 * Usuário e senha chegam via variáveis de ambiente (Kubernetes Secret), nunca via arquivo versionado.
 */
@Validated
@ConfigurationProperties(prefix = "delinea")
public record DelineaProperties(
        @NotBlank String baseUrl,
        @NotBlank String username,
        @NotBlank String password,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        List<@Valid SecretRef> secrets) {

    public DelineaProperties {
        secrets = secrets == null ? List.of() : List.copyOf(secrets);
    }

    /** Referência a um campo de secret: GET /api/v1/secrets/{id}/fields/{field}. */
    public record SecretRef(@Positive int id, @NotBlank String field) {
    }
}
