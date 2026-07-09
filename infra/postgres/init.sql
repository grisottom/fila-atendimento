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
    numero INTEGER NOT NULL,
    localizacao VARCHAR(200),
    UNIQUE(agencia_id, numero)
);

CREATE TABLE sala (
    id SERIAL PRIMARY KEY,
    agencia_id VARCHAR(50) NOT NULL REFERENCES agencia(id),
    nome VARCHAR(100) NOT NULL,
    localizacao VARCHAR(200)
);

CREATE TABLE sala_painel (
    sala_id INTEGER NOT NULL REFERENCES sala(id),
    painel_id INTEGER NOT NULL REFERENCES painel(id),
    PRIMARY KEY (sala_id, painel_id)
);

CREATE TABLE pessoa (
    cpf BIGINT PRIMARY KEY,
    nome VARCHAR(200) NOT NULL
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
    agencia_id VARCHAR(50) NOT NULL REFERENCES agencia(id),
    cpf BIGINT NOT NULL REFERENCES pessoa(cpf),
    nome_pessoa VARCHAR(200) NOT NULL,
    servico_id VARCHAR(50) NOT NULL REFERENCES servico(id),
    senha VARCHAR(5) NOT NULL,
    horario_agendado TIMESTAMP,
    horario_chegada TIMESTAMP NOT NULL DEFAULT NOW(),
    status VARCHAR(20) NOT NULL DEFAULT 'AGUARDANDO',
    sala_id INTEGER REFERENCES sala(id),
    atendente_username VARCHAR(100),
    horario_chamada TIMESTAMP,
    horario_inicio_atendimento TIMESTAMP,
    horario_fim_atendimento TIMESTAMP,
    posicao_fila INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_fila_agencia_status ON fila_atendimento(agencia_id, status);
CREATE INDEX idx_fila_prioridade ON fila_atendimento(agencia_id, status, horario_agendado NULLS LAST, posicao_fila);

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

-- Painéis e salas de exemplo
INSERT INTO painel (agencia_id, numero, localizacao) VALUES
('agencia-01', 1, 'Térreo'),
('agencia-01', 2, '2º Andar'),
('agencia-02', 1, 'Térreo');

INSERT INTO sala (agencia_id, nome, localizacao) VALUES
('agencia-01', 'Sala 1', 'Térreo - Fundos'),
('agencia-01', 'Sala 2', 'Térreo - Lateral'),
('agencia-01', 'Sala 3', '2º Andar'),
('agencia-02', 'Sala 1', 'Térreo - Fundos');

INSERT INTO sala_painel (sala_id, painel_id) VALUES
(1, 1), -- Sala 1 agencia-01 → Painel 1
(2, 1), -- Sala 2 agencia-01 → Painel 1
(3, 2), -- Sala 3 agencia-01 → Painel 2
(4, 3); -- Sala 1 agencia-02 → Painel 1

-- ===========================================
-- DADOS DE TESTE: PESSOAS E AGENDAMENTOS
-- ===========================================

INSERT INTO pessoa (cpf, nome) VALUES
(11122233344, 'Maria Silva'),
(22233344455, 'João Santos'),
(33344455566, 'Ana Oliveira'),
(44455566677, 'Carlos Souza'),
(55566677788, 'Fernanda Lima'),
(66677788899, 'Roberto Costa'),
(77788899900, 'Juliana Pereira'),
(88899900011, 'Marcos Almeida'),
(99900011122, 'Patricia Rocha'),
(10011122233, 'Lucas Ferreira'),
(11122233300, 'Beatriz Mendes'),
(22233344411, 'Ricardo Gomes'),
(33344455522, 'Camila Ribeiro'),
(44455566633, 'Eduardo Martins'),
(55566677744, 'Larissa Barbosa'),
(66677788855, 'Thiago Cardoso'),
(77788899966, 'Amanda Nascimento'),
(88899900077, 'Felipe Araujo'),
(99900011188, 'Gabriela Dias'),
(10011122299, 'Daniel Monteiro');

-- Agendamentos para agencia-01 (hoje)
INSERT INTO agendamento (cpf, agencia_id, servico_id, data_hora) VALUES
(11122233344, 'agencia-01', 'servico-basico', CURRENT_DATE + INTERVAL '9 hours'),
(22233344455, 'agencia-01', 'servico-normal-01', CURRENT_DATE + INTERVAL '9 hours 30 minutes'),
(33344455566, 'agencia-01', 'servico-normal-02', CURRENT_DATE + INTERVAL '10 hours'),
(44455566677, 'agencia-01', 'servico-especial-01', CURRENT_DATE + INTERVAL '10 hours 30 minutes'),
(55566677788, 'agencia-01', 'servico-basico', CURRENT_DATE + INTERVAL '11 hours'),
(66677788899, 'agencia-01', 'servico-normal-01', CURRENT_DATE + INTERVAL '11 hours 30 minutes'),
(77788899900, 'agencia-01', 'servico-especial-01', CURRENT_DATE + INTERVAL '13 hours'),
(88899900011, 'agencia-01', 'servico-basico', CURRENT_DATE + INTERVAL '14 hours'),
(99900011122, 'agencia-01', 'servico-normal-02', CURRENT_DATE + INTERVAL '14 hours 30 minutes'),
(10011122233, 'agencia-01', 'servico-especial-01', CURRENT_DATE + INTERVAL '15 hours');

-- Agendamentos para agencia-02 (hoje)
INSERT INTO agendamento (cpf, agencia_id, servico_id, data_hora) VALUES
(11122233300, 'agencia-02', 'servico-basico', CURRENT_DATE + INTERVAL '9 hours'),
(22233344411, 'agencia-02', 'servico-normal-01', CURRENT_DATE + INTERVAL '9 hours 30 minutes'),
(33344455522, 'agencia-02', 'servico-normal-02', CURRENT_DATE + INTERVAL '10 hours'),
(44455566633, 'agencia-02', 'servico-especial-01', CURRENT_DATE + INTERVAL '10 hours 30 minutes'),
(55566677744, 'agencia-02', 'servico-basico', CURRENT_DATE + INTERVAL '11 hours'),
(66677788855, 'agencia-02', 'servico-normal-01', CURRENT_DATE + INTERVAL '11 hours 30 minutes'),
(77788899966, 'agencia-02', 'servico-normal-02', CURRENT_DATE + INTERVAL '13 hours'),
(88899900077, 'agencia-02', 'servico-basico', CURRENT_DATE + INTERVAL '14 hours'),
(99900011188, 'agencia-02', 'servico-especial-01', CURRENT_DATE + INTERVAL '14 hours 30 minutes'),
(10011122299, 'agencia-02', 'servico-normal-01', CURRENT_DATE + INTERVAL '15 hours');
