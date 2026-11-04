package com.fila.apiatendimento.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "fila_atendimento")
public class FilaAtendimento {

    /** Status normal de item pronto para ser chamado (publicado na fila do broker). */
    public static final String STATUS_AGUARDANDO = "AGUARDANDO";

    /**
     * Status de item de agendamento RETIDO pela pré-triagem: a pessoa foi
     * recepcionada com muita antecedência (antecedência &gt; 2 × intervalo da
     * agenda), então o item recebe senha e é persistido, mas NÃO é publicado na
     * fila do broker ainda. O {@code OutboxPublisher} o publica (mudando para
     * {@link #STATUS_AGUARDANDO}) quando entra na janela do horário ou quando a
     * agência está parada (ninguém CHAMANDO/EM_ATENDIMENTO).
     */
    public static final String STATUS_AGENDADO_FUTURO = "AGENDADO_FUTURO";

    // Categorias de prioridade (atendimento prioritário). O nível JMS (9/8/7/6/4)
    // é derivado da categoria + agendado/espontâneo na publicação.
    /** Sem prioridade legal: atendimento comum (nível JMS 6 agendado / 4 espontâneo). */
    public static final String PRIORIDADE_NENHUMA = "NENHUMA";
    /** Prioridade por lei (60-79, deficiência, TEA, gestante, etc.): nível 8/7. */
    public static final String PRIORIDADE_POR_LEI = "POR_LEI";
    /** Prioridade especial (80+ anos): nível 9. */
    public static final String PRIORIDADE_ESPECIAL = "ESPECIAL";
    /** Emergência médica: nível 9 (topo, junto com especial). */
    public static final String PRIORIDADE_EMERGENCIA = "EMERGENCIA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // Identificador de correlação gerado na aplicação ANTES do insert. É o que
    // viaja na mensagem do broker, permitindo publicar e persistir numa única
    // escrita (a PK técnica continua sendo gerada pelo banco via IDENTITY).
    @Column(name = "triagem_uuid", nullable = false, unique = true, updatable = false)
    private java.util.UUID triagemUuid;

    @Column(name = "agencia_id")
    private String agenciaId;

    private Long cpf;

    @Column(name = "nome_pessoa")
    private String nomePessoa;

    @Column(name = "servico_id")
    private String servicoId;

    private String senha;

    @Column(name = "horario_agendado")
    private LocalDateTime horarioAgendado;

    @Column(name = "horario_chegada")
    private LocalDateTime horarioChegada;

    private String status;

    @Column(name = "categoria_prioridade", nullable = false)
    private String categoriaPrioridade = PRIORIDADE_NENHUMA;

    @Column(name = "estacao_id")
    private Integer estacaoId;

    @Column(name = "atendente_username")
    private String atendenteUsername;

    @Column(name = "horario_chamada")
    private LocalDateTime horarioChamada;

    @Column(name = "horario_inicio_atendimento")
    private LocalDateTime horarioInicioAtendimento;

    @Column(name = "horario_fim_atendimento")
    private LocalDateTime horarioFimAtendimento;

    @Column(name = "posicao_fila")
    private Integer posicaoFila;

    @Column(name = "publicado_no_broker", nullable = false)
    private boolean publicadoNoBroker = false;

    // Contador anti-loop de reprocessamentos a partir da DLQ. Persiste no item
    // porque o JMSXDeliveryCount do broker reseta a cada ciclo de DLQ.
    @Column(name = "tentativas_dlq", nullable = false)
    private int tentativasDlq = 0;

    @Version
    private Integer version;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public java.util.UUID getTriagemUuid() { return triagemUuid; }
    public void setTriagemUuid(java.util.UUID triagemUuid) { this.triagemUuid = triagemUuid; }
    public String getAgenciaId() { return agenciaId; }
    public void setAgenciaId(String agenciaId) { this.agenciaId = agenciaId; }
    public Long getCpf() { return cpf; }
    public void setCpf(Long cpf) { this.cpf = cpf; }
    public String getNomePessoa() { return nomePessoa; }
    public void setNomePessoa(String nomePessoa) { this.nomePessoa = nomePessoa; }
    public String getServicoId() { return servicoId; }
    public void setServicoId(String servicoId) { this.servicoId = servicoId; }
    public String getSenha() { return senha; }
    public void setSenha(String senha) { this.senha = senha; }
    public LocalDateTime getHorarioAgendado() { return horarioAgendado; }
    public void setHorarioAgendado(LocalDateTime horarioAgendado) { this.horarioAgendado = horarioAgendado; }
    public LocalDateTime getHorarioChegada() { return horarioChegada; }
    public void setHorarioChegada(LocalDateTime horarioChegada) { this.horarioChegada = horarioChegada; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCategoriaPrioridade() { return categoriaPrioridade; }
    public void setCategoriaPrioridade(String categoriaPrioridade) { this.categoriaPrioridade = categoriaPrioridade; }
    public Integer getEstacaoId() { return estacaoId; }
    public void setEstacaoId(Integer estacaoId) { this.estacaoId = estacaoId; }
    public String getAtendenteUsername() { return atendenteUsername; }
    public void setAtendenteUsername(String atendenteUsername) { this.atendenteUsername = atendenteUsername; }
    public LocalDateTime getHorarioChamada() { return horarioChamada; }
    public void setHorarioChamada(LocalDateTime horarioChamada) { this.horarioChamada = horarioChamada; }
    public LocalDateTime getHorarioInicioAtendimento() { return horarioInicioAtendimento; }
    public void setHorarioInicioAtendimento(LocalDateTime horarioInicioAtendimento) { this.horarioInicioAtendimento = horarioInicioAtendimento; }
    public LocalDateTime getHorarioFimAtendimento() { return horarioFimAtendimento; }
    public void setHorarioFimAtendimento(LocalDateTime horarioFimAtendimento) { this.horarioFimAtendimento = horarioFimAtendimento; }
    public Integer getPosicaoFila() { return posicaoFila; }
    public void setPosicaoFila(Integer posicaoFila) { this.posicaoFila = posicaoFila; }
    public boolean isPublicadoNoBroker() { return publicadoNoBroker; }
    public void setPublicadoNoBroker(boolean publicadoNoBroker) { this.publicadoNoBroker = publicadoNoBroker; }
    public int getTentativasDlq() { return tentativasDlq; }
    public void setTentativasDlq(int tentativasDlq) { this.tentativasDlq = tentativasDlq; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
