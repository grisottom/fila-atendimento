package com.fila.apiatendimento.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agendamento")
public class Agendamento {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private Long cpf;

    @Column(name = "agencia_id")
    private String agenciaId;

    @Column(name = "servico_id")
    private String servicoId;

    @Column(name = "data_hora")
    private LocalDateTime dataHora;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Long getCpf() { return cpf; }
    public void setCpf(Long cpf) { this.cpf = cpf; }
    public String getAgenciaId() { return agenciaId; }
    public void setAgenciaId(String agenciaId) { this.agenciaId = agenciaId; }
    public String getServicoId() { return servicoId; }
    public void setServicoId(String servicoId) { this.servicoId = servicoId; }
    public LocalDateTime getDataHora() { return dataHora; }
    public void setDataHora(LocalDateTime dataHora) { this.dataHora = dataHora; }
}
