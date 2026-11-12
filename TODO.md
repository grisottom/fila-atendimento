# TODO

## Prioridade por lei / especial / emergência (atendimento prioritário)

**Objetivo:** atender cidadãos com prioridade legal à frente dos demais, usando a
prioridade JMS que a fila já respeita (maior primeiro; FIFO desempata dentro do nível).

**Categorias e níveis JMS:**

| JMS | Categoria | Origem | Retém? (pré-triagem) |
|-----|-----------|--------|----------------------|
| 9 | **Especial** (80+ anos) **ou** **Emergência médica** | idade / manual | **Nunca** (ignora retenção) |
| 8 | **Por lei** — agendado | idade 60-79 / manual | Sim (regra 2× intervalo) |
| 7 | **Por lei** — espontâneo | idade 60-79 / manual | n/a (espontâneo não retém) |
| 6 | Comum — agendado | — | Sim |
| 4 | Comum — espontâneo | — | n/a |

- **Por lei (manual):** pessoa com deficiência, TEA, gestante, lactante, acompanhante
  de criança de colo, obeso, mobilidade reduzida. Informadas na **tela de triagem**.
- **Por idade (automático):** 80+ → especial (9); 60-79 → por lei (8/7). Derivada da
  **data de nascimento** (nova coluna em `pessoa`).
- **Emergência médica (manual):** topo, agendado ou não.

**Regra de combinação:** uma pessoa pode acumular fontes de prioridade (idade +
seleção manual). Vale a **maior** (ex.: 65 anos + emergência → emergência/9; 82 anos
+ qualquer coisa → especial/9). "Emergência suplanta idade" é caso particular disso.

**Topo (decisão 2.i):** especial (80+) e emergência médica ficam **ambas no nível 9**
— empatam, FIFO (ordem de chegada) desempata. Não há nível acima de 9 no JMS;
"emergência suplanta idade" aqui significa que a emergência nunca é rebaixada por
idade, não que passa à frente de um 80+ que chegou antes.

**Interação com a pré-triagem (abaixo):**
- Especial/emergência (9) **nunca** são retidas — `deveReterAgendado` deve retornar
  `false` para elas, independente do horário. Logo, a **categoria precisa ser
  calculada ANTES** da decisão de retenção na recepção.
- Por lei agendado (8) **respeita** a retenção como qualquer agendado (compete em
  ordem com outros "por lei" só depois de publicado).
- Isso **substitui** o "9 fixo para agendado / 4 para espontâneo" atual: a publicação
  passa a receber o **nível calculado** (TriagemService e OutboxPublisher).

**Modelagem:**
- `pessoa`: nova coluna `data_nascimento` (DATE). Popular massa de teste (`init.sql`)
  cobrindo as faixas (alguns 80+, alguns 60-79, resto comum).
- `fila_atendimento`: persistir a **categoria** de prioridade (enum/string, semântica
  estável: `NENHUMA`/`POR_LEI`/`ESPECIAL`/`EMERGENCIA`), não o número — o nível JMS
  (9/8/7/6/4) é derivado na publicação (+ agendado/espontâneo). Replay e listagens
  precisam da categoria.
- `TriagemRequest` (DTO) + tela de Triagem: campos das condições "por lei" e marcador
  de "emergência médica".
- `TriagemService.recepcionar`: calcula categoria (idade + manual, maior vence),
  decide retenção considerando a categoria, passa o nível à publicação.
- `publicarNaQueueAgencia`: troca o `boolean agendado` pelo nível calculado.
- `OutboxPublisher.liberarAgendadosFuturos`: publica o retido liberado com o nível
  correto (não mais "9 fixo porque é agendado").

**Passos previstos:**
- [ ] `pessoa.data_nascimento` (entidade + `init.sql` + massa de teste).
- [ ] Coluna de categoria em `fila_atendimento` (entidade + `init.sql`).
- [ ] Classificador de prioridade (idade + seleção manual → categoria; maior vence).
- [ ] `TriagemRequest` + UI de triagem (condições por lei + emergência).
- [ ] `TriagemService`: integrar categoria à retenção e à publicação.
- [ ] `publicarNaQueueAgencia` e `OutboxPublisher`: usar o nível calculado.
- [ ] Documentar em `README-api-atendimento` (seção de prioridade/ordenação) e no
  `requisitos.txt` se couber.

---

## Exibir a prioridade nas listas da UI (triagem e atendimento)

**Contexto:** a `categoria_prioridade` já é calculada e persistida na triagem e
governa a ordem de chamada, mas **não aparece em nenhuma lista da UI**. O operador não
tem como ver quem é prioritário (por lei / especial / emergência) nem conferir a
classificação — hoje só é visível indiretamente pela ordem em que os itens são
chamados.

**Objetivo:** adicionar uma **coluna de prioridade** nas listas:
- **Triagem — "Aguardando atendimento"** (`Triagem.jsx`, alimentada por
  `GET /api/triagem/atendimentos/{agenciaId}`).
- **Atendimento — "Fila Aguardando"** (`Atendimento.jsx`, alimentada por
  `GET /api/atendimento/fila-disponivel`).

**Pré-requisito (backend):** o `AtendimentoResponse` **não** carrega hoje a
`categoriaPrioridade` — as duas listas usam esse DTO. Então é preciso:
- [ ] Incluir `categoriaPrioridade` no `AtendimentoResponse` (campo + construtores de
  conveniência, mantendo as chamadas existentes).
- [ ] Preencher o campo em `TriagemService.listarAtendimentosDoDiaEsperando` e em
  `AtendimentoService.listarFilaDisponivel` (hoje passam `toResponse(f, ...)` sem a
  categoria).

**Front:**
- [ ] Nova coluna "Prioridade" nas duas tabelas, com rótulo amigável
  (ex.: Emergência / Especial (80+) / Por lei / — ) e destaque visual para os
  prioritários (cor/ícone), análogo ao tratamento do status `AGENDADO_FUTURO`.
- [ ] (Opcional) ordenar/realçar os prioritários no topo da lista visual, lembrando
  que a ordem real de chamada é decidida pela fila JMS, não pela tabela.

> Observação: é só exibição — não muda a lógica de prioridade (já implementada). O
> `AGENDADO_FUTURO` continua aparecendo como "Aguardando horário"; as duas colunas
> (status e prioridade) são independentes.

---

## Pré-triagem: reter agendados com muita antecedência antes de publicar na fila

**Problema:** entre dois agendados, a ordem de atendimento hoje é decidida pela fila
JMS (prioridade + FIFO). Como todos os agendados recebem a mesma prioridade (9), o
desempate é a ordem de chegada à triagem — não o horário agendado. Resultado: quem
chega muito antecipado para um horário distante é atendido antes de quem tem horário
mais próximo mas chegou depois.

> Exemplo: pessoa1 (horário 9:15, tria 9:05) deveria ser atendida antes da pessoa2
> (horário 9:30, tria 9:00). Hoje, a pessoa2 é chamada primeiro (triou antes → FIFO).

**Ideia (simples, sem lock e sem reordenar a fila):** na triagem, um agendado só é
publicado no broker quando está **dentro de uma janela** do seu horário. Quem chega
com antecedência maior recebe a senha normalmente, mas o item fica **retido**
(`AGENDADO_FUTURO`, não publicado); entra na fila quando:

1. **o tempo passou e ele entrou na janela** (`now() >= horarioAgendado − 2×intervalo`); **ou**
2. **a agência está parada** — não há nenhum item `CHAMANDO`/`EM_ATENDIMENTO` na
   agência (ninguém sendo atendido): não faz sentido segurar o antecipado se não há
   ninguém em atendimento.

Dentro da janela (ou horário já vencido) e para espontâneos, o comportamento é o
atual (publica na triagem).

> **Nota sobre o gatilho 2 (agência parada):** no arranque do dia, antes da primeira
> chamada, ninguém está `CHAMANDO`/`EM_ATENDIMENTO` → todos os `AGENDADO_FUTURO`
> elegíveis podem ser liberados de uma vez no primeiro ciclo do outbox. Isso é
> intencional (se não há ninguém atendendo, não há por que segurar), mas torna
> **crítica** a ordenação por horário na publicação (ver passos) para que esse lote
> entre na fila na ordem certa. Escopo de "parada" definido como **agência inteira**.

> **Liberação por "atendente livre" foi adiada** (diferente do gatilho 2 acima). Ela
> dependeria de saber quem está **presente e ocioso**, o que não existe no sistema:
> "iniciar estação" é ação só do front (grava em `localStorage`; o backend nunca
> registra atendente↔estação), e o único sinal server-side é o *atendimento ativo*,
> que indica quem está **ocupado**, não quem está **livre**. Fica para depois da
> modelagem de presença de atendente (ver item "Presença/sessão do atendente" abaixo).

> **Limite conhecido da Abordagem 1 (reter, sem reordenar a fila):** a retenção + a
> ordenação no outbox corrigem o desempate **dentro de um lote liberado no mesmo
> ciclo** (ex.: a rajada da abertura da agência). Não há garantia de ordem **global**
> por horário: um retido que fica elegível depois de outro de horário mais cedo já ter
> sido publicado entra **atrás** dele na fila (FIFO), e o outbox não reordena o que já
> está na fila. Resolver o desempate de forma global exigiria mover a decisão de "quem
> é o próximo" da fila JMS para o banco (`ORDER BY horario_agendado`) — reabrindo a
> escolha de projeto da fila JMS. Fora do escopo desta abordagem; registrado como
> trade-off aceito.

**Regra da janela (parametrizada pelo intervalo da agenda):** a janela de retenção
é derivada do **intervalo entre agendamentos** da agenda, não um valor fixo chutado.

- Config única do sistema `atendimento.intervalo-agendamento-min` (default **15**),
  representando o espaçamento típico entre horários da agenda.
- **Janela de retenção = 2 × intervalo** (com default 15 → janela de **30 min**).
- Decisão na triagem, dado `antecedencia = horario_agendado − horario_chegada`:
  - `antecedencia > janela` → **retém** (`AGENDADO_FUTURO`).
  - `antecedencia ≤ janela` ou horário vencido → **publica** agora (comportamento atual).
  - espontâneo → **publica** agora (inalterado).

> Racional do "2× intervalo": com janela ≥ intervalo, o agendado seguinte já estaria
> dentro da janela ao chegar, e a retenção quase nunca dispararia. Usar o dobro do
> intervalo dá folga para segurar só quem chega bem adiantado (> 2 horários à frente),
> sem prender quem está a um ou dois passos do seu horário.

> Massa de testes: `infra/postgres/init.sql` usa agenda de **15 em 15 min** (10
> agendamentos por agência, 09:00→11:15), alinhada ao default do intervalo para
> exercitar a retenção (quem chega cedo para um horário distante fica > 30 min
> adiantado → retido; entra na fila quando o relógio o coloca dentro dos 30 min).

**Por que encaixa bem na arquitetura:** controla **quando** cada item entra na fila,
em vez de tentar ordenar a fila por horário (que a fila JMS não sabe fazer — ordena
por prioridade + FIFO). Mantém a fila JMS fazendo só o que faz bem. E a varredura
periódica que publica pendentes **já existe**: o `OutboxPublisher` (a cada 3–7s).

**Passos previstos:**
- [ ] Modelar o "retido de propósito" com um **status próprio `AGENDADO_FUTURO`**
  (decidido), separado de `publicado_no_broker=false`. Hoje `publicado_no_broker=false`
  significa "falhou ao publicar, republicar o quanto antes"; o status próprio evita
  sobrecarregar essa flag e **não toca em nenhuma query existente** que filtra por
  `AGUARDANDO` (consumo, outbox atual, listagens). O retido fica `status=AGENDADO_FUTURO`
  + `publicado_no_broker=false`, invisível para a varredura de falha de publicação
  (que busca `...AndStatus("AGUARDANDO")`). Verificar se há CHECK constraint em
  `status` no `infra/postgres/init.sql` — se houver, incluir `AGENDADO_FUTURO`.
- [ ] `TriagemService.recepcionar`: para agendado, decidir entre publicar inline
  (dentro da janela `2 × intervalo` / vencido) ou deixar retido (antecedência maior
  que a janela). Espontâneo segue publicando normal.
- [ ] `OutboxPublisher`: avaliar os retidos (`AGENDADO_FUTURO`) **ordenados por
  `horario_agendado` ASC** e publicar cada um que esteja elegível, na ordem — assim o
  lote liberado no mesmo ciclo entra na fila JMS na ordem de horário (a fila é FIFO
  entre mensagens de mesma prioridade). Elegível quando **(1)** entrou na janela
  (`now() >= horarioAgendado − 2×intervalo`) **ou (2)** a agência está parada (nenhum
  `CHAMANDO`/`EM_ATENDIMENTO` na agência). Ao publicar, muda o status para
  `AGUARDANDO` e marca `publicado_no_broker=true`. Preservar intacto o caminho atual
  de "falha de publicação" (`AGUARDANDO` + `publicado_no_broker=false`), que é uma
  varredura **separada** — não confundir com a dos retidos.
- [ ] Parametrizar o **intervalo da agenda** em `atendimento.intervalo-agendamento-min`
  (default 15) e derivar a janela como `2 × intervalo` (default → 30 min). A janela
  não é configurada diretamente: deriva do intervalo, mantendo uma única fonte de verdade.
  O gatilho "agência parada" não tem parâmetro (é só a ausência de itens
  `CHAMANDO`/`EM_ATENDIMENTO` na agência).
- [ ] Revisar impacto na listagem da triagem ("Aguardando atendimento") e no que o
  atendente/painel enxergam para itens retidos (mostrar como "aguardando horário"?).
- [ ] Revisar interação com idempotência/DLQ já documentadas (um item retido não tem
  mensagem no broker; o consumo e o reprocessamento continuam valendo só após a
  publicação).

> Decisões de projeto que embasam esta escolha (prioridade JMS 0–9, precedência sobre
> FIFO, por que não usar lock no banco nem reordenar/rejeitar na fila) estão
> documentadas em [README-api-atendimento.md](README-api-atendimento.md#prioridade-e-ordenação-da-fila).

---

## Presença/sessão do atendente (pré-requisito para liberação oportunista da pré-triagem)

**Contexto:** hoje "iniciar estação" é ação **só do front** (`Atendimento.jsx` resolve
o `estacaoId` e grava em `localStorage`). O backend **não** registra que o atendente
assumiu a estação — ele só descobre a estação embutida no `POST /api/atendimento/chamar`.
Não há `POST` de iniciar-estação, nem heartbeat de atendente, nem associação
persistida atendente↔estação. O único sinal server-side de atividade é o *atendimento
ativo* (`CHAMANDO`/`EM_ATENDIMENTO`), que diz quem está **ocupado**, não quem está
**presente e ocioso**.

**Motivação:** a pré-triagem (acima) quer, como otimização, soltar um agendado retido
antes da janela **quando há atendente livre para o serviço**. Isso exige um conceito
de presença/ociosidade do atendente que não existe. Também é útil por si só (saber
quem está trabalhando, em qual estação, liberar a estação ao sair).

**Esboço (a detalhar quando for priorizado):**
- [ ] Endpoint para **iniciar estação** no backend (registra atendente↔estação↔agência),
  hoje inexistente.
- [ ] **Desativar/encerrar** a sessão da estação (logout, troca de estação, inatividade).
- [ ] **Heartbeat/expiração** da sessão do atendente (análogo ao `painel-heartbeat`),
  para distinguir "presente e ocioso" de "foi embora".
- [ ] Derivar "atendente livre para o serviço" = presente + apto (permissão) + sem
  atendimento ativo. Alimenta a liberação oportunista da pré-triagem.

---

## Login via IdP OAuth2/OIDC externo (SSO em `sso.apps.localtest.me`)

**Contexto:** hoje o chart do fila-atendimento implanta o próprio Keycloak (realm,
clients, usuários e temas) e as APIs só validam tokens porque buscam as chaves pelo
Service interno (`JWK_SET_URI=http://fila-keycloak:8080/...`); só o `issuer` usa a URL
pública. Dentro dos pods, `*.apps.localtest.me` resolve para `::1` (connection refused),
então sem esse atalho as APIs não conseguem validar nenhum token.

**Objetivo:** tratar o IdP como **externo**, como será no cenário real: a aplicação não
implanta nem conhece o Keycloak por dentro do cluster; só recebe issuer, client_ids e,
se for o caso, a CA do IdP. No laboratório o IdP é um Keycloak que nós controlamos
(namespace e host próprios). Os papéis (`admin`/`atendente`) e o atributo `agencia`
continuam vindo do token do IdP.

**O que o dono do IdP fornece (cenário real):** URL do issuer, os client_ids
(`fila-painel`, `fila-atendimento`), redirect URIs/web origins cadastradas para os
nossos hosts e a CA, se for corporativa. **Sem client secret:** os dois apps são SPAs
(client público com Authorization Code + PKCE); secret só faria sentido com um backend
fazendo login (BFF) ou chamadas serviço a serviço (client credentials).

**Decisões tomadas:**
- **IdP:** Keycloak 24 em modo `start` (produção), com Postgres próprio no namespace
  `sso`, em `https://sso.apps.localtest.me:9443`. Fica no projeto
  `k3d-cluster-istio-rancher` (fora do chart da aplicação).
- **Realm:** importado do `realm-export.json` atual (roles, client scope `agencia`,
  usuários e temas painel/atendimento). O import só acontece se o realm **não existir**:
  depois da primeira subida, mudanças são pelo console/API admin (o Postgres persiste
  entre reinícios; só se perde ao recriar o cluster).
- **Clients:** redirect URIs e web origins só dos hosts `fila-painel` e
  `fila-atendimento`; PKCE S256 obrigatório; post-logout redirect. Mantido
  `directAccessGrantsEnabled` (usado pelos scripts de teste; num IdP real costuma vir
  desligado).
- **Porta 9443 mantida** (não 443): no host, a 443 pode estar ocupada ou exigir admin.
  Como o `iss` do token é comparado como texto, o issuer precisa ser idêntico dentro e
  fora do cluster: `https://sso.apps.localtest.me:9443/realms/fila-atendimento`.
- **DNS no cluster:** ConfigMap `coredns-custom` (o CoreDNS do k3s já importa
  `/etc/coredns/custom/*.override`; hoje o ConfigMap não existe) com uma regra
  `rewrite` de `*.apps.localtest.me` para o Service interno do gateway (abaixo). Não
  altera o ConfigMap `coredns` gerenciado pelo k3s nem o DNS fora do cluster.
- **Porta no cluster:** Service `ClusterIP` novo em `istio-system`, com o mesmo
  seletor do `istio-ingressgateway` (`app=istio-ingressgateway`,
  `istio=ingressgateway`), portas `9443→443` e `443→443`. O Service do gateway
  instalado pelo Helm não é alterado.
- **CA:** a CA do laboratório (`my-cluster-ca`, secret `wildcard-apps-ca`) é entregue
  à aplicação como ConfigMap `idp-ca-bundle`; um initContainer monta o truststore da
  JVM das APIs. No cenário real é aí que entra a CA corporativa.
- **Escopo:** só o exemplo `fila-atendimento-AMQ-Operator` (namespace `fila-amq`).

**Passos previstos:**

Plataforma (`k3d-cluster-istio-rancher`):
- [ ] Namespace `sso`: Postgres + Keycloak 24 (`start`, `KC_HOSTNAME` público,
  `KC_HTTP_ENABLED`, `KC_PROXY_HEADERS=xforwarded`), Ingress `sso.apps.localtest.me`,
  realm e temas importados.
- [ ] Service `ClusterIP` em `istio-system` (`9443→443`, `443→443`).
- [ ] ConfigMap `coredns-custom` com o `rewrite` para esse Service; testar a resolução
  e um `GET` no issuer a partir de um pod.
- [ ] ConfigMap `idp-ca-bundle` com a CA no namespace da aplicação.

Chart (`deploy/chart`):
- [ ] Remover o Keycloak: `templates/keycloak.yaml`, realm e temas em `files/`, Ingress
  e chaves de admin no Secret.
- [ ] Bloco `oidc` no `values.yaml`: `issuerUri`, `jwkSetUri` (públicos) e nome do
  ConfigMap da CA (opcional; só para CA privada).
- [ ] APIs: `ISSUER_URI`/`JWK_SET_URI` pela URL pública via HTTPS; initContainer que
  gera o truststore a partir do ConfigMap da CA.

Frontends (`app-atendimento`, `app-painel`):
- [ ] `VITE_KEYCLOAK_URL=https://sso.apps.localtest.me:9443` (parâmetro da pipeline).
- [ ] Realm e client_id como build-args opcionais (default: valores atuais).
- [ ] `pkceMethod: "S256"` explícito no `keycloak.init`.

Migração (sem deixar o login quebrado no meio):
- [ ] 1) Subir SSO, DNS, Service e CA. 2) Rodar o `04` (imagens novas já apontando
  para o SSO). 3) Rodar o `02` (chart sem Keycloak); o ArgoCD remove o Keycloak antigo.

Validação:
- [ ] Login nos dois apps, triagem, "chamar próximo" e evento no painel (SSE).
- [ ] Confirmar que nenhum pod da aplicação fala com o Keycloak por Service interno.
- [ ] Atualizar o README do exemplo (URLs, Keycloak externo) e os READMEs do projeto.

> Recomendação para depois: validar `aud`/`azp` nas APIs. Hoje elas aceitam qualquer
> token do realm, o que importa mais quando o IdP é compartilhado com outros sistemas.
