# Design: consumo da fila JMS, garantias de entrega e DLQ

Documento de referência consolidando a investigação sobre o `chamarProximo`
(`AtendimentoService`), a interação entre a transação JPA e a sessão JMS, o padrão
outbox (`OutboxPublisher`) e o papel da Dead Letter Queue (DLQ) do Artemis.

> Status: **investigação concluída, implementação pendente.** O sistema continua
> operando no "Cenário C" descrito abaixo. Nenhuma refatoração foi aplicada ainda.

---

## 1. Contexto

Quando o atendente clica em "Chamar Próximo":

1. `AtendimentoService.chamarProximo` é `@Transactional` (transação JPA).
2. Dentro dela, consome uma mensagem da fila `agencia.<id>.fila` via
   `jmsQueueTemplate.receiveSelected(queue, selector)` com selector por permissão.
3. Localiza o `FilaAtendimento` pelo `triagemUuid`, muda status para CHAMANDO,
   salva e publica nos tópicos dos painéis.

Config relevante (`JmsConfig`): `jmsQueueTemplate` com `sessionTransacted=true` e
`receiveTimeout=2000`. O `OutboxPublisher` varre a cada 3–7s itens
`publicado_no_broker=false AND status='AGUARDANDO'` e republica na fila.

---

## 2. O problema observado

### 2.1 Exaustão do pool de conexões (HikariCP)

Sob carga (teste com `MAX_PARALELO=40`, pool default de 10 conexões), surgiram:

```
HTTP=500 "Unable to acquire JDBC Connection ... request timed out after 30000ms"
HTTP=500 "Could not open JPA EntityManager for transaction"
```

**Causa raiz:** o `receiveSelected` fica dentro da transação `@Transactional`. A
transação abre e **retém uma conexão JDBC** antes do receive. O receive bloqueia
por até 2s esperando o broker (filas vazias retornam após o `receiveTimeout`).
Resultado: cada chamada prende uma conexão ociosa por ~2s. Com 40 chamadas
concorrentes contra 10 conexões, o pool satura e requisições estouram o timeout
de 30s do Hikari.

Agravante: no `catch`, `outboxPublisher.resetarPublicacao` roda em
`REQUIRES_NEW`, pegando uma **segunda** conexão no momento do erro.

### 2.2 Duplicatas na fila

Observado: filas acumulando mais mensagens que itens AGUARDANDO no banco. O botão
"Republicar" (triagem) e o outbox publicam sem checar duplicatas. A idempotência
do `chamarProximo` (checagem de status) descarta duplicatas, mas elas poluem a fila.

---

## 3. Experimentos realizados

Com fault injection após o `save` (e `receiveTimeout` reduzido a 500ms), medindo
fila JMS, DLQ e tabela `fila_atendimento`.

### Exp. 1 e 2 — falha na transação com `sessionTransacted=true`

Clique único que falha após o `receiveSelected` consumir a mensagem.

Resultado (idêntico nas duas execuções): `MESSAGES_ADDED` subiu apenas +1 (a
republicação do outbox) e `MESSAGE_COUNT` terminou em 2.

**Conclusão:** com `sessionTransacted=true`, o rollback da transação JPA
**sincroniza o rollback da sessão JMS** — a mensagem consumida **volta** para a
fila (redelivery, não incrementa `MESSAGES_ADDED`). Além disso, o
`resetarPublicacao` + outbox **também** republicam → **mensagem duplicada**
(inofensiva pela idempotência, mas é retrabalho e polui a fila).

> Logo, no `catch` do `chamarProximo` o `resetarPublicacao` é **redundante**: o
> rollback do JMS já devolve a mensagem. Ele é a causa da duplicata nesse caminho.

### Exp. 3 — DLQ com `receiveSelected` sob demanda (outbox DESLIGADO)

Fault sempre ativa; outbox desligado (early return em `publicarPendentes`). Uma
triagem, depois ~11 cliques em "Chamar Próximo".

Resultado: após ~10 cliques, a mensagem da fila `agencia.<id>.fila` foi para a
**DLQ** (`DLQ.MESSAGE_COUNT=1`, fila principal `MESSAGE_COUNT=0`), e a resposta ao
atendente mudou para "Nenhum atendimento na fila". Item no banco permaneceu
AGUARDANDO com `publicado_no_broker=false`.

Config do broker (inferida do `broker.xml`): `dead-letter-address=DLQ`,
`redelivery-delay=0`, `max-delivery-attempts` default = **10**.

**Conclusões:**
- O `JMSXDeliveryCount` **acumula entre cliques** (sessões `receiveSelected`
  distintas) — o Artemis persiste o contador no header da mensagem.
- O mecanismo de poison-message / DLQ **funciona mesmo no consumo sob demanda**.
- **O outbox agressivo compete com a DLQ e vence**: se estivesse ligado, teria
  reinjetado o item (`publicado_no_broker=false`) antes das 10 tentativas se
  esgotarem, impedindo a ida à DLQ e criando o loop infinito observado em 2.1/2.2.

---

## 4. Decisões de arquitetura

### 4.1 Consumo: tirar o `receiveSelected` da transação JPA (Opção B)

Mover o receive para **fora** da transação JPA, mas mantê-lo numa **sessão JMS
transacionada com commit após o sucesso do banco**:

- O receive não retém conexão JDBC durante os 2s de espera → resolve a exaustão
  do pool (seção 2.1).
- O ack/commit da mensagem fica atrelado ao sucesso da transação do banco. Em
  qualquer falha (rollback, exceção, **crash do processo**), a sessão JMS não
  commita e o broker **reentrega** — sem depender de código de compensação nem de
  o processo sobreviver.

Garantia mais forte que a atual e sem a redundância do `resetarPublicacao` no catch.

### 4.2 "Nenhum painel ativo" é falha de PROCESSAMENTO

Exibir a chamada no painel é requisito funcional (chamar sem painel = atendimento
quebrado). Portanto **não** é recusa de negócio silenciosa: é falha de
processamento e entra no fluxo normal de redelivery/DLQ. A mensagem não é
confirmada, o item permanece AGUARDANDO, o broker reentrega. Se persistir, vai à
DLQ — tornando visível o problema operacional ("agência sem painel").

> Consequência: descartada a ideia de tratar esse caso como `NegocioException`/409
> à parte. É falha de processamento como qualquer outra.

### 4.3 Redelivery + DLQ como mecanismo primário de retry

Deixar o broker fazer o retry com teto (`max-delivery-attempts`) e cair na DLQ.
Isso dá limite de tentativas e um ponto único de visibilidade/controle, em vez do
retry infinito do outbox agressivo.

### 4.4 Handler de DLQ (listener contínuo)

Adicionar um listener da fila `DLQ` (padrão `DefaultMessageListenerContainer`, como
`ReplayListener`/`HeartbeatListener`). Para cada mensagem:

1. Lê `triagemUuid`, busca o `FilaAtendimento`.
2. **Idempotência:** se o item já saiu de AGUARDANDO, descarta.
3. **Política anti-loop:** consulta um contador de ciclos pela DLQ (ver 4.6). Se
   excedeu o limite, para e sinaliza falha definitiva (log/alerta/estado), sem
   reinjetar.
4. Caso contrário, recoloca o item para republicação chamando
   `outboxPublisher.resetarPublicacao(filaId)` (marca `publicado_no_broker=false`;
   o outbox republica no próximo ciclo).

O listener **deve** usar sessão transacionada (ack atrelado ao sucesso): se o
`resetarPublicacao` falhar por banco fora, a mensagem **permanece na DLQ** e é
reprocessada quando o banco voltar. **Nunca** auto-ack na DLQ (perderia a
mensagem, que é a última linha de defesa baseada em mensagem).

### 4.5 Outbox como rede de ÚLTIMO recurso

O `OutboxPublisher` **permanece**, mas deixa de ser republicador proativo
agressivo. Fonte de verdade = **banco** (`publicado_no_broker=false AND
AGUARDANDO`). É a única camada que não depende de nenhuma mensagem existir no
broker, cobrindo:

- Mensagem nunca publicada (falha na própria triagem).
- Falha de banco prolongada em que até o handler de DLQ falha, **ou** em que a
  própria mensagem da DLQ se perdeu (DLQ tem limites: `max-redelivery-records=1`
  no broker, expiração, purga).

Ajuste recomendado: varredura **mais espaçada** e/ou com **janela de tempo** (ex.:
só itens pendentes há mais de alguns minutos), para **não competir** com a DLQ.

### 4.6 Contador anti-loop (DEVE ir para o banco — comprovado por teste)

> **Correção após experimento (Exp. 4).** A ideia inicial era usar o
> `JMSXDeliveryCount` do broker, sem coluna no banco. **O teste refutou isso.**

Observação empírica (Exp. 4, três ciclos consecutivos): toda vez que a mensagem é
dead-lettered e reinjetada na fila original (via DlqListener → resetarPublicacao →
outbox), ela volta com **`JMSXDeliveryCount = 1`**. O contador do broker **reseta a
cada ciclo de DLQ** — ele conta reentregas *dentro* de uma mesma permanência na
fila, não atravessa o ciclo fila→DLQ→fila.

Consequência: uma política anti-loop baseada em `JMSXDeliveryCount` **nunca
dispara** (count perpetuamente = 1) → **loop infinito** fila → ~N falhas → DLQ →
reprocessa → republica → fila → ... (observado no teste com a fault determinística).

**Decisão:** o contador anti-loop **precisa persistir no item**, pois é o único
estado que sobrevive ao ciclo de DLQ (e ao próprio broker):

- Coluna nova em `fila_atendimento`, ex.: `tentativas_dlq INT DEFAULT 0`.
- O `DlqListener` incrementa a cada reprocessamento.
- Ao exceder o teto (`dlq-max-reprocessos`), **para de republicar** e marca falha
  definitiva (ex.: status `FALHA` ou flag dedicada), removendo o item do loop e
  sinalizando para intervenção/observabilidade.

Nota: isso acopla o anti-loop ao banco. No cenário "banco fora", o DlqListener já
falha no `resetarPublicacao` e a mensagem fica retida na DLQ (sessão transacionada)
— ou seja, o incremento e a verificação do contador acontecem na mesma transação do
reprocessamento, então não há inconsistência: ou tudo commita, ou a mensagem volta
para a DLQ.

---

### 4.7 Esboço do `DlqListener`

Segue o padrão de `ReplayListener`/`HeartbeatListener` (container criado em
`@PostConstruct`), com **duas diferenças essenciais** ditadas pela natureza da DLQ:

1. **Sessão transacionada** (`container.setSessionTransacted(true)`): o ack da
   mensagem fica atrelado ao sucesso do processamento. Os outros listeners usam
   auto-ack e **engolem** exceções (logam e seguem) — o que descarta a mensagem em
   erro. Na DLQ isso seria perda da última linha de defesa.
2. **Propagar exceção em falha** (NÃO capturar e logar): se o processamento falhar
   (ex.: `resetarPublicacao` com banco fora), a exceção sobe, o container faz
   rollback da sessão e a mensagem **permanece na DLQ** para reprocessamento.

Fluxo do `onMessage`:

```
1. Ler triagemUuid da mensagem (property). Se ausente/inválido → log e ACK
   (descarta: mensagem irrecuperável, não adianta reprocessar).
2. Buscar FilaAtendimento por triagemUuid.
      - não encontrado → log e ACK (nada a fazer).
      - status != AGUARDANDO (idempotência) → log e ACK (já tratado).
3. Anti-loop: incrementar fila.tentativas_dlq (contador PERSISTENTE no item — ver
   4.6; o JMSXDeliveryCount NÃO serve, reseta a cada ciclo de DLQ).
      - se tentativas_dlq > dlq-max-reprocessos:
          → falha definitiva: marcar status=FALHA (ou flag), log de alerta.
            ACK (sai da DLQ sem republicar) — quebra o loop.
4. Caso reprocessável:
      outboxPublisher.resetarPublicacao(filaId);  // marca publicado=false
      // o OutboxPublisher republica no próximo ciclo (rede de último recurso).
      ACK (processamento concluído com sucesso).
   Se o banco estiver fora (incremento/reset LANÇA):
      exceção propaga → rollback da sessão → mensagem FICA na DLQ.
```

> O incremento de `tentativas_dlq`, a verificação do teto e o `resetarPublicacao`
> devem ocorrer na **mesma transação** do handler, para serem atômicos (ou tudo
> commita e a mensagem sai da DLQ, ou rollback e a mensagem permanece).

Pontos de atenção:
- O "ACK" acima é implícito: com sessão transacionada, retornar normalmente do
  `onMessage` comita a sessão (remove da DLQ); lançar exceção faz rollback (mantém).
- A semântica de `JMSXDeliveryCount` após reinjeção pela DLQ precisa ser validada
  empiricamente (ver 4.6). Enquanto não validada, o LIMITE funciona como salvaguarda.
- `resetarPublicacao` continua em `REQUIRES_NEW` — ok aqui, pois é o próprio ato de
  negócio do handler (não há transação externa competindo como no catch antigo).

## 5. Modelo de quatro camadas de garantia

Cada camada cobre a falha da anterior:

| # | Camada | Cobre | Fonte de verdade |
|---|--------|-------|------------------|
| 1 | Rollback da sessão JMS (commit pós-banco) | Falha/rollback na transação do atendimento | Mensagem no broker |
| 2 | Redelivery + DLQ do broker | Falhas repetidas de processamento (poison), com teto | Mensagem no broker |
| 3 | Handler de DLQ (transacionado) | Reprocessar quando o banco volta; parar em loop | DLQ + banco |
| 4 | Outbox (varredura) | Mensagem nunca publicada; mensagem/DLQ perdida; banco fora prolongado | **Banco** |

A camada 4 é insubstituível porque é a única cuja fonte de verdade é o banco, não
uma mensagem no broker.

---

## 6. Mudanças concretas previstas (quando implementar)

1. `AtendimentoService.chamarProximo`: tirar o `receiveSelected` da transação JPA;
   gerenciar sessão JMS com commit pós-banco (Opção B). **Remover** o
   `resetarPublicacao` do `catch` (redundante — causa duplicata).
2. Novo `DlqListener` (listener contínuo, transacionado) + `DlqReprocessor`
   (método `@Transactional` único) com idempotência e política anti-loop. Em vez de
   chamar `resetarPublicacao`, o reprocessor faz, na mesma transação: valida
   idempotência, incrementa `tentativas_dlq`, e decide entre reprocessar
   (`publicado_no_broker=false` → outbox republica) ou **falha definitiva**
   (status `FALHA_DLQ`, quebrando o loop). Coluna `tentativas_dlq` adicionada em
   `fila_atendimento` (entidade + init.sql).
3. `OutboxPublisher`: varredura espaçada / com janela de tempo (rede de último
   recurso), para não competir com a DLQ.
4. Política anti-loop no handler lendo `JMSXDeliveryCount` da mensagem (sem coluna
   nova no banco — ver 4.6).
5. Pool do Hikari: revisar `maximum-pool-size` conforme concorrência real (a
   Opção B já reduz drasticamente a pressão).

---

### 4.8 "Republicar" (intervenção manual) zera o anti-loop

O botão "Republicar" da triagem é a intervenção humana que dá nova chance a um
item. Ao republicar:

- Aceita itens em `AGUARDANDO` **e** `FALHA_DLQ` (antes só `AGUARDANDO`).
- **Zera `tentativas_dlq`** e força `status=AGUARDANDO` — o item ganha um ciclo de
  tentativas automáticas renovado. Sem isso, um item ressuscitado estouraria o teto
  rápido demais, ignorando a intervenção.
- Itens `FALHA_DLQ` passam a aparecer na listagem "Aguardando atendimento"
  (`listarAtendimentosDoDiaEsperando` inclui `FALHA_DLQ`) e no front o botão
  Republicar aparece para esse status; o status é destacado em vermelho.

## 7. Pendências de limpeza (alterações TEMPORÁRIAS de teste em aberto)

Introduzidas durante a investigação, **devem ser revertidas**:

- `JmsConfig.jmsQueueTemplate`: `receiveTimeout` 500 → **2000**.
- `AtendimentoService`: remover a **fault injection** (`if (FAULT_ATIVA) throw ...`)
  e o campo `FAULT_ATIVA`.
- `OutboxPublisher.publicarPendentes`: remover o **early return** `if (true) return;`
  (religar o outbox).
- `application.yml`: `atendimento.exigir-painel-ativo` false → **true** (ver 4.2:
  no modelo final esse enforcement vira falha de processamento; reavaliar o papel
  da flag).
- Dados residuais do teste: item `ROI6K` (agencia-01, AGUARDANDO,
  `publicado_no_broker=false`) e a mensagem parada na **DLQ**.
