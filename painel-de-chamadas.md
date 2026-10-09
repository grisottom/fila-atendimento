# Painel de Chamadas — Frontend

## Descrição

Aplicação web que exibe, em tempo real, os atendimentos em curso de uma agência, mostrando à pessoa qual senha está sendo chamada e para qual estação (Mesa, Guichê ou Sala) ela deve se dirigir. É o elo visual entre a chamada feita pelo atendente e a pessoa que aguarda na agência.

Cada painel é identificado pela combinação agência + número do painel. É exclusivo do papel admin e tem uma tela de configuração (informa o número do painel; a agência vem do token) e a tela de chamadas.

## Objetivo

Apresentar de forma imediata quem está sendo chamado e onde a pessoa deve ser atendida.

## Atores

- **Admin (gerente da agência):** ativa o painel informando o número.
- **Pessoa em espera:** acompanha visualmente as chamadas.

## Tecnologia

Aplicação frontend em React.

## Regras principais

- Cada painel vê somente as chamadas da sua agência e do seu número.
- A chamada exibida indica a senha e a estação de atendimento (ex.: Mesa 3, Guichê 1).
- Ao (re)conectar, restaura na tela os atendimentos que já estavam em curso.

## Estados exibidos

- **Chamando:** senha e estação em destaque.
- **Em atendimento:** pessoa já atendida na estação.
- **Ausente:** pessoa chamada que não compareceu.

## Dependências

Consome as chamadas do backend do Painel via SSE.

---

# Painel de Chamadas — Backend

## Descrição

Serviço que conecta o frontend do painel às chamadas em tempo real. Mantém a conexão SSE com cada painel e entrega os eventos correspondentes à agência e ao número daquele painel. Não persiste dados.

## Objetivo

Sustentar a entrega em tempo real das chamadas ao painel correto, incluindo a recuperação de estado quando um painel reconecta.

## Atores

- **Frontend do Painel:** consumidor das chamadas via SSE.

## Tecnologia

Aplicação backend em Spring Boot, sem persistência própria.

## Regras principais

- Cada conexão de painel recebe apenas os eventos da sua agência e do seu número.
- Um painel recebe apenas as chamadas dos serviços a ele associados.
- Ao reconectar um painel, dispara a recuperação dos atendimentos ativos para restaurar a tela.

## Dependências

- Recebe as chamadas por meio do broker de mensageria.
- As chamadas têm origem na macrofuncionalidade de **Atendimento**, que decide quando e para quais painéis publicar cada evento. Ela não faz parte desta macrofuncionalidade e é citada apenas como a fonte externa das chamadas.

---

# Painel de Chamadas — Mensageria

## Descrição

Camada de mensageria assíncrona que transporta as chamadas até o painel correto. Cada painel tem um canal próprio, e a mensagem publicada é entregue apenas à instância onde aquele painel está conectado.

## Objetivo

Desacoplar a origem das chamadas (Atendimento) da entrega ao painel, garantindo que cada evento chegue ao painel certo, com suporte a múltiplos painéis simultâneos.

## Atores

- **Backend do Painel:** assina o canal do painel para receber as chamadas.
- **Origem das chamadas (Atendimento):** publica os eventos nos canais dos painéis.

## Tecnologia

Broker de mensageria ActiveMQ Artemis (modelo pub/sub por canal dinâmico).

## Regras principais

- Cada painel possui um canal próprio, isolado por agência e número.
- A mensagem de uma chamada é entregue somente ao painel de destino.
- O canal do painel é criado quando ele se conecta e descartado quando desconecta.

## Dependências

Recebe eventos publicados pela macrofuncionalidade de **Atendimento** e os entrega ao backend do Painel.
