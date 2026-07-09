package com.fila.apiatendimento.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "fila_atendimento")
public class FilaAtendimento {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

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

    @Column(name = "sala_id")
    private Integer salaId;

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

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
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
    public Integer getSalaId() { return salaId; }
    public void setSalaId(Integer salaId) { this.salaId = salaId; }
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
}
