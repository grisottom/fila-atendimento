package com.fila.apiatendimento.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "painel")
public class Painel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "agencia_id")
    private String agenciaId;

    @Column(name = "numero_painel")
    private Integer numeroPainel;
    private String localizacao;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getAgenciaId() { return agenciaId; }
    public void setAgenciaId(String agenciaId) { this.agenciaId = agenciaId; }
    public Integer getNumeroPainel() { return numeroPainel; }
    public void setNumeroPainel(Integer numeroPainel) { this.numeroPainel = numeroPainel; }
    public String getLocalizacao() { return localizacao; }
    public void setLocalizacao(String localizacao) { this.localizacao = localizacao; }
}
