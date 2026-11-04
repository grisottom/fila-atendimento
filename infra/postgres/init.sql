-- Schema separado para o Keycloak
CREATE SCHEMA IF NOT EXISTS keycloak;

-- ===========================================
-- TABELAS DA APLICAÇÃO
-- ===========================================

CREATE TABLE agencia (
    id VARCHAR(50) PRIMARY KEY,
    nome VARCHAR(200) NOT NULL
);

CREATE TABLE servico (
    id VARCHAR(50) PRIMARY KEY,
    nome VARCHAR(200) NOT NULL,
    permissao_exigida VARCHAR(50) NOT NULL
);

CREATE TABLE painel (
    id SERIAL PRIMARY KEY,
    agencia_id VARCHAR(50) NOT NULL REFERENCES agencia(id),
    numero_painel INTEGER NOT NULL,
    localizacao VARCHAR(200),
    ultimo_heartbeat TIMESTAMP,
    UNIQUE(agencia_id, numero_painel)
);

CREATE TABLE estacao (
    id SERIAL PRIMARY KEY,
    agencia_id VARCHAR(50) NOT NULL REFERENCES agencia(id),
    tipo_estacao VARCHAR(10) NOT NULL CHECK (tipo_estacao IN ('MESA', 'GUICHE', 'SALA')),
    numero_estacao INTEGER NOT NULL,
    localizacao VARCHAR(200),
    UNIQUE(agencia_id, tipo_estacao, numero_estacao)
);

CREATE TABLE paineis_servicos (
    id SERIAL PRIMARY KEY,
    painel_id INTEGER NOT NULL REFERENCES painel(id) ON DELETE CASCADE,
    servico_id VARCHAR(50) NOT NULL REFERENCES servico(id) ON DELETE CASCADE,
    UNIQUE(painel_id, servico_id)
);

CREATE TABLE pessoa (
    cpf BIGINT PRIMARY KEY,
    nome VARCHAR(200) NOT NULL,
    -- Usada para derivar a prioridade por idade na triagem: 80+ → ESPECIAL,
    -- 60-79 → POR_LEI. Nullable: pessoas sem data cadastrada não recebem
    -- prioridade por idade (só manual, se marcada).
    data_nascimento DATE
);

CREATE TABLE agendamento (
    id SERIAL PRIMARY KEY,
    cpf BIGINT NOT NULL REFERENCES pessoa(cpf),
    agencia_id VARCHAR(50) NOT NULL REFERENCES agencia(id),
    servico_id VARCHAR(50) NOT NULL REFERENCES servico(id),
    data_hora TIMESTAMP NOT NULL
);

CREATE TABLE fila_atendimento (
    id SERIAL PRIMARY KEY,
    triagem_uuid UUID NOT NULL UNIQUE,
    agencia_id VARCHAR(50) NOT NULL REFERENCES agencia(id),
    cpf BIGINT NOT NULL REFERENCES pessoa(cpf),
    nome_pessoa VARCHAR(200) NOT NULL,
    servico_id VARCHAR(50) NOT NULL REFERENCES servico(id),
    senha VARCHAR(5) NOT NULL,
    horario_agendado TIMESTAMP,
    horario_chegada TIMESTAMP NOT NULL DEFAULT NOW(),
    status VARCHAR(20) NOT NULL DEFAULT 'AGUARDANDO',
    -- Categoria de prioridade do atendimento (atendimento prioritário). O nível
    -- JMS (9/8/7/6/4) é derivado disto + agendado/espontâneo na publicação.
    categoria_prioridade VARCHAR(20) NOT NULL DEFAULT 'NENHUMA'
        CHECK (categoria_prioridade IN ('NENHUMA', 'POR_LEI', 'ESPECIAL', 'EMERGENCIA')),
    estacao_id INTEGER REFERENCES estacao(id),
    atendente_username VARCHAR(100),
    horario_chamada TIMESTAMP,
    horario_inicio_atendimento TIMESTAMP,
    horario_fim_atendimento TIMESTAMP,
    posicao_fila INTEGER NOT NULL DEFAULT 0,
    publicado_no_broker BOOLEAN NOT NULL DEFAULT FALSE,
    -- Contador anti-loop: nº de vezes que o item foi reprocessado a partir da DLQ.
    -- Persiste no item porque o JMSXDeliveryCount do broker reseta a cada ciclo de
    -- DLQ (comprovado em teste). Ao exceder o teto, o item vira FALHA_DLQ.
    tentativas_dlq INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE atendente (
    id SERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    agencia_id VARCHAR(50) NOT NULL REFERENCES agencia(id),
    UNIQUE(username, agencia_id)
);

CREATE TABLE permissoes_atendente (
    id SERIAL PRIMARY KEY,
    atendente_id INTEGER NOT NULL REFERENCES atendente(id) ON DELETE CASCADE,
    permissao VARCHAR(50) NOT NULL CHECK (permissao IN ('basica', 'normal', 'especial')),
    UNIQUE(atendente_id, permissao)
);

CREATE INDEX idx_atendente_username_agencia ON atendente(username, agencia_id);

CREATE INDEX idx_fila_agencia_status ON fila_atendimento(agencia_id, status);
CREATE INDEX idx_fila_prioridade ON fila_atendimento(agencia_id, status, horario_agendado NULLS LAST, posicao_fila);
CREATE INDEX idx_fila_outbox_pendente ON fila_atendimento(id) WHERE status = 'AGUARDANDO' AND publicado_no_broker = FALSE;

-- ===========================================
-- DADOS INICIAIS
-- ===========================================

INSERT INTO agencia (id, nome) VALUES
('agencia-01', 'Agência 01 - Centro'),
('agencia-02', 'Agência 02 - Norte');

INSERT INTO servico (id, nome, permissao_exigida) VALUES
('servico-basico', 'Serviço Básico', 'basica'),
('servico-normal-01', 'Serviço Normal 01', 'normal'),
('servico-normal-02', 'Serviço Normal 02', 'normal'),
('servico-especial-01', 'Serviço Especial 01', 'especial');

-- Painéis e estações de exemplo
INSERT INTO painel (agencia_id, numero_painel, localizacao) VALUES
('agencia-01', 1, 'Térreo'),
('agencia-01', 2, '2º Andar'),
('agencia-02', 1, 'Térreo');

INSERT INTO estacao (agencia_id, tipo_estacao, numero_estacao, localizacao) VALUES
('agencia-01', 'MESA', 1, 'Térreo - Fundos'),
('agencia-01', 'MESA', 2, 'Térreo - Lateral'),
('agencia-01', 'GUICHE', 1, '2º Andar'),
('agencia-02', 'MESA', 1, 'Térreo - Fundos'),
('agencia-02', 'GUICHE', 1, 'Térreo - Lateral');

-- Associação painéis ↔ serviços
INSERT INTO paineis_servicos (painel_id, servico_id) VALUES
-- Painel 1 da agencia-01 (Térreo): serviço-basico e serviço-normal-01
((SELECT id FROM painel WHERE agencia_id = 'agencia-01' AND numero_painel = 1), 'servico-basico'),
((SELECT id FROM painel WHERE agencia_id = 'agencia-01' AND numero_painel = 1), 'servico-normal-01'),
((SELECT id FROM painel WHERE agencia_id = 'agencia-01' AND numero_painel = 1), 'servico-normal-02'),
-- Painel 2 da agencia-01 (2º Andar): serviço-especial-01
((SELECT id FROM painel WHERE agencia_id = 'agencia-01' AND numero_painel = 2), 'servico-especial-01'),
-- Painel 1 da agencia-02 (Térreo): todos os serviços
((SELECT id FROM painel WHERE agencia_id = 'agencia-02' AND numero_painel = 1), 'servico-basico'),
((SELECT id FROM painel WHERE agencia_id = 'agencia-02' AND numero_painel = 1), 'servico-normal-01'),
((SELECT id FROM painel WHERE agencia_id = 'agencia-02' AND numero_painel = 1), 'servico-normal-02'),
((SELECT id FROM painel WHERE agencia_id = 'agencia-02' AND numero_painel = 1), 'servico-especial-01');

-- ===========================================
-- DADOS DE TESTE: PESSOAS E AGENDAMENTOS
-- ===========================================

-- data_nascimento cobre as faixas de prioridade por idade:
--   80+  → ESPECIAL  (nascidos até ~1945)
--   60-79 → POR_LEI  (nascidos ~1947-1966)
--   <60  → comum     (nascidos 1967+)
-- Alguns propositalmente NULL (sem data) para testar "sem prioridade por idade".
INSERT INTO pessoa (cpf, nome, data_nascimento) VALUES
(11122233344, 'Maria Silva',        '1940-03-12'), -- 80+ ESPECIAL
(22233344455, 'João Santos',        '1944-11-05'), -- 80+ ESPECIAL
(33344455566, 'Ana Oliveira',       '1955-07-20'), -- 60-79 POR_LEI
(44455566677, 'Carlos Souza',       '1962-01-30'), -- 60-79 POR_LEI
(55566677788, 'Fernanda Lima',      '1980-09-15'), -- comum
(66677788899, 'Roberto Costa',      '1975-05-10'), -- comum
(77788899900, 'Juliana Pereira',    '1990-12-01'), -- comum
(88899900011, 'Marcos Almeida',     NULL),         -- sem data
(99900011122, 'Patricia Rocha',     '1958-04-22'), -- 60-79 POR_LEI
(10011122233, 'Lucas Ferreira',     '1995-08-08'), -- comum
(11122233300, 'Beatriz Mendes',     '1938-02-18'), -- 80+ ESPECIAL
(22233344411, 'Ricardo Gomes',      '1966-10-03'), -- 60-79 POR_LEI
(33344455522, 'Camila Ribeiro',     '1985-06-25'), -- comum
(44455566633, 'Eduardo Martins',    NULL),         -- sem data
(55566677744, 'Larissa Barbosa',    '2000-03-14'), -- comum
(66677788855, 'Thiago Cardoso',     '1972-11-28'), -- comum
(77788899966, 'Amanda Nascimento',  '1950-09-09'), -- 60-79 POR_LEI
(88899900077, 'Felipe Araujo',      '1943-07-07'), -- 80+ ESPECIAL
(99900011188, 'Gabriela Dias',      '1988-01-19'), -- comum
(10011122299, 'Daniel Monteiro',    '1963-12-30'); -- 60-79 POR_LEI

-- Agendamentos para agencia-01 (hoje) — intervalo de 15 min entre horários
INSERT INTO agendamento (cpf, agencia_id, servico_id, data_hora) VALUES
(11122233344, 'agencia-01', 'servico-basico', CURRENT_DATE + INTERVAL '9 hours'),
(22233344455, 'agencia-01', 'servico-normal-01', CURRENT_DATE + INTERVAL '9 hours 15 minutes'),
(33344455566, 'agencia-01', 'servico-normal-02', CURRENT_DATE + INTERVAL '9 hours 30 minutes'),
(44455566677, 'agencia-01', 'servico-especial-01', CURRENT_DATE + INTERVAL '9 hours 45 minutes'),
(55566677788, 'agencia-01', 'servico-basico', CURRENT_DATE + INTERVAL '10 hours'),
(66677788899, 'agencia-01', 'servico-normal-01', CURRENT_DATE + INTERVAL '10 hours 15 minutes'),
(77788899900, 'agencia-01', 'servico-especial-01', CURRENT_DATE + INTERVAL '10 hours 30 minutes'),
(88899900011, 'agencia-01', 'servico-basico', CURRENT_DATE + INTERVAL '10 hours 45 minutes'),
(99900011122, 'agencia-01', 'servico-normal-02', CURRENT_DATE + INTERVAL '11 hours'),
(10011122233, 'agencia-01', 'servico-especial-01', CURRENT_DATE + INTERVAL '11 hours 15 minutes');

-- Agendamentos para agencia-02 (hoje) — intervalo de 15 min entre horários
INSERT INTO agendamento (cpf, agencia_id, servico_id, data_hora) VALUES
(11122233300, 'agencia-02', 'servico-basico', CURRENT_DATE + INTERVAL '9 hours'),
(22233344411, 'agencia-02', 'servico-normal-01', CURRENT_DATE + INTERVAL '9 hours 15 minutes'),
(33344455522, 'agencia-02', 'servico-normal-02', CURRENT_DATE + INTERVAL '9 hours 30 minutes'),
(44455566633, 'agencia-02', 'servico-especial-01', CURRENT_DATE + INTERVAL '9 hours 45 minutes'),
(55566677744, 'agencia-02', 'servico-basico', CURRENT_DATE + INTERVAL '10 hours'),
(66677788855, 'agencia-02', 'servico-normal-01', CURRENT_DATE + INTERVAL '10 hours 15 minutes'),
(77788899966, 'agencia-02', 'servico-normal-02', CURRENT_DATE + INTERVAL '10 hours 30 minutes'),
(88899900077, 'agencia-02', 'servico-basico', CURRENT_DATE + INTERVAL '10 hours 45 minutes'),
(99900011188, 'agencia-02', 'servico-especial-01', CURRENT_DATE + INTERVAL '11 hours'),
(10011122299, 'agencia-02', 'servico-normal-01', CURRENT_DATE + INTERVAL '11 hours 15 minutes');

-- ===========================================
-- DADOS INICIAIS: ATENDENTES E PERMISSÕES
-- ===========================================

INSERT INTO atendente (username, agencia_id) VALUES
('atend-triagem', 'agencia-01'),
('atend-triagem-2', 'agencia-01'),
('atend-normal', 'agencia-01'),
('atend-especial', 'agencia-01'),
('atend-all', 'agencia-01'),
('atend-all-2', 'agencia-01');

INSERT INTO permissoes_atendente (atendente_id, permissao) VALUES
((SELECT id FROM atendente WHERE username = 'atend-triagem' AND agencia_id = 'agencia-01'), 'basica'),
((SELECT id FROM atendente WHERE username = 'atend-triagem-2' AND agencia_id = 'agencia-01'), 'basica'),
((SELECT id FROM atendente WHERE username = 'atend-normal' AND agencia_id = 'agencia-01'), 'normal'),
((SELECT id FROM atendente WHERE username = 'atend-especial' AND agencia_id = 'agencia-01'), 'especial'),
((SELECT id FROM atendente WHERE username = 'atend-all' AND agencia_id = 'agencia-01'), 'basica'),
((SELECT id FROM atendente WHERE username = 'atend-all' AND agencia_id = 'agencia-01'), 'normal'),
((SELECT id FROM atendente WHERE username = 'atend-all' AND agencia_id = 'agencia-01'), 'especial'),
((SELECT id FROM atendente WHERE username = 'atend-all-2' AND agencia_id = 'agencia-01'), 'basica'),
((SELECT id FROM atendente WHERE username = 'atend-all-2' AND agencia_id = 'agencia-01'), 'normal'),
((SELECT id FROM atendente WHERE username = 'atend-all-2' AND agencia_id = 'agencia-01'), 'especial');
