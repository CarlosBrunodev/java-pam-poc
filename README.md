# java-pam-poc

POC em Spring Boot (Java 21) que autentica no **Delinea Secret Server** com usuário e senha e lê
campos de secrets via REST API, exibindo os valores em `GET /secrets`.

> ⚠️ O endpoint `/secrets` devolve os valores em texto puro. Isso só é aceitável nesta POC porque os
> dados são fictícios. Não replique esse endpoint em aplicações reais.

Arquitetura, requisitos e como integrar em outro backend: [docs/ARQUITETURA.md](docs/ARQUITETURA.md).

---

## Pré-requisitos

| Ferramenta | Versão usada na POC | Para quê |
|---|---|---|
| JDK | 21 | compilar e rodar localmente |
| Maven | 3.9+ | build |
| Docker com `buildx` | 27+ | gerar a imagem |
| `kubectl` | compatível com o cluster | deploy |
| Acesso de rede | `https://vli.secretservercloud.com` (443) | autenticar e ler secrets |
| Usuário no Delinea | com permissão de leitura nos secrets usados | credencial da aplicação |

---

## Configuração

Toda a configuração vem de variáveis de ambiente (mapeadas em `src/main/resources/application.yaml`):

| Variável | Obrigatória | Padrão | Descrição |
|---|---|---|---|
| `DELINEA_BASE_URL` | não | `https://vli.secretservercloud.com` | URL do Secret Server, sem barra no final |
| `DELINEA_USERNAME` | **sim** | — | usuário do Delinea |
| `DELINEA_PASSWORD` | **sim** | — | senha do usuário |
| `DELINEA_SECRETS_0_ID` | não | `7880` | ID do secret lido em `GET /secrets` |
| `DELINEA_SECRETS_0_FIELD` | não | `clientsecret` | slug do campo desse secret |

Para ler mais secrets, adicione `DELINEA_SECRETS_1_ID`, `DELINEA_SECRETS_1_FIELD`, e assim por diante.
Se `DELINEA_USERNAME` ou `DELINEA_PASSWORD` faltarem, a aplicação **não sobe**: a validação acontece na inicialização.

---

## Executar localmente

```bash
export DELINEA_USERNAME='<USUARIO>'
export DELINEA_PASSWORD='<SENHA>'

mvn spring-boot:run
```

Ou pelo JAR:

```bash
mvn -B package -DskipTests
java -jar target/java-pam-poc-0.0.1-SNAPSHOT.jar
```

Testar:

```bash
curl -s localhost:8080/actuator/health
curl -s localhost:8080/secrets
curl -s 'localhost:8080/secrets?id=7880&field=clientsecret'
```

Exemplo de resposta de `/secrets`:

```json
[
  { "secretId": 7880, "field": "clientsecret", "value": "<valor>", "error": null }
]
```

Se o Delinea recusar a leitura (sem permissão, ID inexistente etc.), `value` vem `null` e `error` traz
o status HTTP e a mensagem do Delinea.

---

## Endpoints

| Método | Path | Descrição |
|---|---|---|
| GET | `/secrets` | lê todos os campos configurados em `delinea.secrets` |
| GET | `/secrets?id={id}&field={slug}` | lê um campo específico |
| GET | `/actuator/health` | status geral com detalhes |
| GET | `/actuator/health/liveness` | usado pela `livenessProbe` / `startupProbe` |
| GET | `/actuator/health/readiness` | usado pela `readinessProbe` |

---

## Executar no Kubernetes

### 1. Gerar e publicar a imagem

```bash
# --platform é obrigatório ao buildar em Mac Apple Silicon (arm64) para nós amd64;
# sem ele o pod falha com "exec format error".
docker buildx build --platform linux/amd64 \
  -t carlosbrunodev/java-pam-poc:0.0.2 --push .
```

Confira a arquitetura dos nós com `kubectl get nodes -L kubernetes.io/arch`.
Sempre use uma **tag nova** a cada build: o Deployment usa `imagePullPolicy: IfNotPresent`, e o nó
não baixa de novo uma tag que já tem em cache.

### 2. Ajustar os manifestos

| Arquivo | O que ajustar |
|---|---|
| `k8s/deployment.yaml` | `image:` com a tag publicada |
| `k8s/configmap.yaml` | URL do Delinea e IDs/campos dos secrets |
| `k8s/ingress.yaml` | `ingressClassName` (`kubectl get ingressclass`) e, se houver cert-manager, o bloco `tls` |

### 3. Criar o Secret com as credenciais

```bash
kubectl apply -f k8s/namespace.yaml

kubectl -n java-pam-poc create secret generic delinea-credentials \
  --from-literal=DELINEA_USERNAME='<USUARIO>' \
  --from-literal=DELINEA_PASSWORD='<SENHA>'
```

O arquivo `k8s/secret.example.yaml` é só um modelo. Não versione o `secret.yaml` preenchido (ele já
está no `.gitignore`).

### 4. Aplicar

```bash
kubectl apply -k k8s/
kubectl -n java-pam-poc rollout status deployment/java-pam-poc
```

### 5. Validar

Via port-forward (não depende de DNS nem de Ingress):

```bash
kubectl -n java-pam-poc port-forward svc/java-pam-poc 8080:80
# em outro terminal
curl -s localhost:8080/actuator/health
curl -s localhost:8080/secrets
```

Via Ingress:

```bash
curl -s http://java-pam.dev.vli.digital/actuator/health
curl -s http://java-pam.dev.vli.digital/secrets
```

---

## Troubleshooting

| Sintoma | Causa provável | Como resolver |
|---|---|---|
| `exec format error` no pod | imagem gerada para arm64 e nó amd64 | build com `--platform linux/amd64` e tag nova |
| Pod em CrashLoop, log com `Binding to target ...DelineaProperties failed` e `must not be blank` | faltou `DELINEA_USERNAME` ou `DELINEA_PASSWORD` | conferir o Secret `delinea-credentials` |
| `/secrets` retorna `error: 400 ... invalid_grant` | usuário ou senha incorretos | conferir credenciais |
| `/secrets` retorna `error: 400 ... Access Denied` | usuário sem permissão no secret, ou ID/slug errado | conferir permissão e slug no Delinea |
| `error: ResourceAccessException ... timed out` | sem saída de rede para o Delinea | liberar egress para `vli.secretservercloud.com:443` |
| Pod com a imagem antiga após novo push | mesma tag + `IfNotPresent` | usar tag nova |

Logs:

```bash
kubectl -n java-pam-poc logs deploy/java-pam-poc -f
```

---

## Estrutura

```
.
├── Dockerfile
├── pom.xml
├── docs/ARQUITETURA.md
├── k8s/
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secret.example.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── ingress.yaml
│   └── kustomization.yaml
└── src/main/
    ├── java/br/com/vli/pam/
    │   ├── PamPocApplication.java
    │   ├── client/DelineaClient.java
    │   ├── client/TokenResponse.java
    │   ├── config/DelineaProperties.java
    │   └── web/SecretsController.java
    └── resources/application.yaml
```
