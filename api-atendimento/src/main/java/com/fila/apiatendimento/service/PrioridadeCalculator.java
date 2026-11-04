package com.fila.apiatendimento.service;

import com.fila.apiatendimento.entity.FilaAtendimento;

import java.time.LocalDate;
import java.time.Period;

/**
 * Regras de prioridade do atendimento (atendimento prioritário por lei/especial/
 * emergência). Centraliza duas conversões:
 *
 *  1. (idade + marcadores manuais da triagem) → CATEGORIA, aplicando "maior vence".
 *  2. (categoria + agendado?) → NÍVEL de prioridade JMS (0-9).
 *
 * Tabela de níveis (a fila entrega maior primeiro; FIFO desempata no mesmo nível):
 *   9  ESPECIAL (80+) ou EMERGENCIA — agendado ou não
 *   8  POR_LEI agendado
 *   7  POR_LEI espontâneo
 *   6  comum (NENHUMA) agendado
 *   4  comum (NENHUMA) espontâneo
 */
public final class PrioridadeCalculator {

    private PrioridadeCalculator() {}

    public static final int PRIO_ESPECIAL_EMERGENCIA = 9;
    public static final int PRIO_POR_LEI_AGENDADO = 8;
    public static final int PRIO_POR_LEI_ESPONTANEO = 7;
    public static final int PRIO_COMUM_AGENDADO = 6;
    public static final int PRIO_COMUM_ESPONTANEO = 4;

    /** Idade (inclusive) a partir da qual a pessoa tem prioridade ESPECIAL. */
    public static final int IDADE_ESPECIAL = 80;
    /** Idade (inclusive) a partir da qual a pessoa tem prioridade POR_LEI por idade. */
    public static final int IDADE_POR_LEI = 60;

    /**
     * Determina a categoria de prioridade combinando a idade (derivada da data de
     * nascimento) com os marcadores manuais da triagem. Vale a MAIOR prioridade.
     *
     * Ordem (maior → menor): EMERGENCIA, ESPECIAL, POR_LEI, NENHUMA.
     * "Emergência suplanta idade" é caso particular de "maior vence".
     *
     * @param dataNascimento data de nascimento, ou null se desconhecida (sem idade).
     * @param hoje           data de referência para o cálculo da idade.
     * @param porLei         marcador manual "prioridade por lei".
     * @param emergencia     marcador manual "emergência médica".
     * @return uma das constantes FilaAtendimento.PRIORIDADE_*.
     */
    public static String categoria(LocalDate dataNascimento, LocalDate hoje,
                                   boolean porLei, boolean emergencia) {
        if (emergencia) {
            return FilaAtendimento.PRIORIDADE_EMERGENCIA;
        }

        Integer idade = dataNascimento != null ? Period.between(dataNascimento, hoje).getYears() : null;

        boolean especialPorIdade = idade != null && idade >= IDADE_ESPECIAL;
        if (especialPorIdade) {
            return FilaAtendimento.PRIORIDADE_ESPECIAL;
        }

        boolean porLeiPorIdade = idade != null && idade >= IDADE_POR_LEI;
        if (porLei || porLeiPorIdade) {
            return FilaAtendimento.PRIORIDADE_POR_LEI;
        }

        return FilaAtendimento.PRIORIDADE_NENHUMA;
    }

    /**
     * Converte a categoria + agendado/espontâneo no nível de prioridade JMS (0-9).
     */
    public static int nivelJms(String categoria, boolean agendado) {
        return switch (categoria) {
            case FilaAtendimento.PRIORIDADE_ESPECIAL, FilaAtendimento.PRIORIDADE_EMERGENCIA ->
                    PRIO_ESPECIAL_EMERGENCIA;
            case FilaAtendimento.PRIORIDADE_POR_LEI ->
                    agendado ? PRIO_POR_LEI_AGENDADO : PRIO_POR_LEI_ESPONTANEO;
            default ->
                    agendado ? PRIO_COMUM_AGENDADO : PRIO_COMUM_ESPONTANEO;
        };
    }

    /**
     * Categorias que NUNCA são retidas pela pré-triagem (entram na fila na hora,
     * mesmo chegando muito adiantadas): ESPECIAL (80+) e EMERGENCIA.
     */
    public static boolean ignoraRetencao(String categoria) {
        return FilaAtendimento.PRIORIDADE_ESPECIAL.equals(categoria)
                || FilaAtendimento.PRIORIDADE_EMERGENCIA.equals(categoria);
    }
}
