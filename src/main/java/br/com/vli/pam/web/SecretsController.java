package br.com.vli.pam.web;

import br.com.vli.pam.client.DelineaClient;
import br.com.vli.pam.config.DelineaProperties;
import br.com.vli.pam.config.DelineaProperties.SecretRef;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * POC: exibe na tela os valores lidos do cofre.
 * ATENÇÃO: expor secrets em HTTP só é aceitável aqui porque os dados são fictícios.
 */
@RestController
public class SecretsController {

    private static final Logger log = LoggerFactory.getLogger(SecretsController.class);

    private final DelineaClient delineaClient;
    private final DelineaProperties properties;

    public SecretsController(DelineaClient delineaClient, DelineaProperties properties) {
        this.delineaClient = delineaClient;
        this.properties = properties;
    }

    /**
     * GET /secrets                          -> lê todos os secrets configurados em delinea.secrets
     * GET /secrets?id=7880&field=clientsecret -> lê um campo específico
     */
    @GetMapping("/secrets")
    public List<SecretResult> secrets(
            @RequestParam(required = false) Integer id,
            @RequestParam(required = false) String field) {

        List<SecretRef> refs = (id != null && field != null)
                ? List.of(new SecretRef(id, field))
                : properties.secrets();

        return refs.stream().map(this::read).toList();
    }

    private SecretResult read(SecretRef ref) {
        try {
            String value = delineaClient.getSecretField(ref.id(), ref.field());
            return new SecretResult(ref.id(), ref.field(), value, null);
        } catch (Exception e) {
            log.error("Falha ao ler secret {} campo {}: {}", ref.id(), ref.field(), DelineaClient.describe(e));
            return new SecretResult(ref.id(), ref.field(), null, DelineaClient.describe(e));
        }
    }

    public record SecretResult(int secretId, String field, String value, String error) {
    }
}
