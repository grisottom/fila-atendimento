# Atendimento — Frontend

## Descrição

Aplicação web usada por atendentes e pelo admin para conduzir todo o ciclo do atendimento presencial numa agência. Reúne três perfis de uso em uma mesma interface: a triagem, que recepciona a pessoa por CPF, consulta o agendamento do dia, gera a senha e insere a pessoa na fila; o atendimento, no qual o atendente informa a estação em que está, chama o próximo da fila e conduz o ciclo (iniciar, finalizar, cancelar ou ausentar quem não compareceu); e a administração, na qual o gerente cadastra painéis e estações, associa serviços a painéis e gerencia as permissões dos atendentes. A agência do usuário vem do token e é exibida como campo somente-leitura em todas as telas.

## Objetivo

Permitir que a agência recepcione as pessoas, organize a fila por serviço e conduza cada atendimento do início ao fim, respeitando a prioridade de agendados sobre espontâneos.

## Atores

- Atendente (permissão básica ou admin): realiza a triagem.
- Atendente (qualquer permissão) e admin: realizam o atendimento.
- Admin (gerente da agência): configura painéis, estações, associações painel↔serviço e permissões dos atendentes.
- Pessoa atendida: recepcionada na triagem e chamada para uma estação.

## Tecnologia

Aplicação frontend em React.

## Regras principais

- A agência vem do token do usuário e é somente-leitura em todas as telas.
- A triagem identifica a pessoa por CPF e, quando existe agendamento do dia, pré-preenche o serviço.
- O atendente informa a estação (Mesa, Guichê ou Sala) antes de chamar o próximo.
- Um atendente só chama serviços compatíveis com as permissões que possui.
- O ciclo do atendimento oferece as ações chamar, iniciar, finalizar, cancelar e ausentar.
- A tela de administração é exclusiva do papel admin.

## Dependências

Consome as operações do backend do Atendimento via API REST.

---

# Atendimento — Backend

## Descrição

Serviço que centraliza a lógica de negócio do atendimento. Na triagem, gera a senha (5 caracteres, maiúsculas e números), registra a pessoa na fila e a disponibiliza para consumo pelos atendentes. No ciclo de atendimento, entrega o próximo da fila conforme as permissões do atendente e a prioridade (agendados antes de espontâneos, e ordem de chegada como desempate), identifica o serviço do atendimento, verifica os painéis associados a esse serviço e conduz as transições de status (chamando, em atendimento, ausente, finalizado, cancelado). Também expõe a configuração usada pelo admin (painéis, estações, associações painel↔serviço e permissões dos atendentes) e publica cada evento de chamada destinado aos painéis. As permissões de atendimento são lidas do banco; a agência e os papéis vêm do token.

## Objetivo

Orquestrar triagem, fila e ciclo de atendimento de forma consistente, garantindo prioridade, controle de permissões e a publicação das chamadas para os painéis do serviço.

## Atores

- Frontend do Atendimento: consumidor das operações via API REST.
- Keycloak: fornece os papéis e a agência do usuário no token.

## Tecnologia

Aplicação backend em Spring Boot.

## Regras principais

- A próxima pessoa da fila é selecionada por permissão do atendente, com agendados tendo prioridade sobre espontâneos e a ordem de chegada como desempate.
- Ao chamar o próximo, identifica o serviço e publica o evento em todos os painéis associados; se o serviço não tiver painel associado, o chamado é recusado com erro.
- As permissões de atendimento (básica, normal, especial) são lidas do banco; para admin, valem todas.
- Toda inserção na fila precisa gerar uma mensagem correspondente para consumo, com garantia de publicação e descarte de duplicatas (idempotência).
- Ao reconectar, um painel solicita a recuperação dos atendimentos ativos, que são republicados para ele.

## Dependências

- Publica as chamadas por meio do broker de mensageria, que as entrega ao backend do Painel.
- Persiste e consulta os dados no banco de dados do Atendimento.

---

# Atendimento — Persistência de dados

## Descrição

Banco de dados que mantém o estado do atendimento na agência. Guarda o cadastro base (agências, serviços, painéis, estações e a associação entre painéis e serviços), os atendentes e suas permissões, as pessoas e seus agendamentos, e a fila de atendimento com o registro de cada senha: status, estação, atendente, horários (chegada, chamada, início e fim) e posição na fila. É a fonte de verdade consultada e atualizada ao longo de todo o ciclo, da triagem à finalização, e serve de base para a recuperação de estado dos painéis.

## Objetivo

Preservar de forma consistente e persistente todo o estado do atendimento, permitindo retomar o processamento e recuperar os atendimentos em curso a qualquer momento.

## Atores

- Backend do Atendimento: lê e grava os dados ao longo do ciclo.

## Tecnologia

Banco de dados relacional PostgreSQL.

## Regras principais

- A fila de atendimento registra o status de cada senha (aguardando, chamando, em atendimento, ausente, finalizado, cancelado) e os respectivos horários.
- O agendamento associa a pessoa a um serviço e a um horário; a ausência de horário indica atendimento espontâneo.
- A associação painel↔serviço define quais painéis recebem os eventos de um dado serviço.
- As permissões do atendente determinam quais serviços ele pode chamar.
- Cada registro de fila indica se sua mensagem já foi disponibilizada para consumo, apoiando a garantia de publicação.

## Dependências

Acessado exclusivamente pelo backend do Atendimento.
