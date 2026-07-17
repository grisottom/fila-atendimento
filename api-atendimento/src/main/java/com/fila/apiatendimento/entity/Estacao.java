package com.fila.apiatendimento.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "estacao")
public class Estacao {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "agencia_id")
    private String agenciaId;

    @Column(name = "tipo_estacao")
    private String tipoEstacao;

    @Column(name = "numero_estacao")
    private Integer numeroEstacao;
    private String localizacao;

    @ManyToOne
    @JoinColumn(name = "painel_id")
    private Painel painel;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getAgenciaId() { return agenciaId; }
    public void setAgenciaId(String agenciaId) { this.agenciaId = agenciaId; }
    public String getTipoEstacao() { return tipoEstacao; }
    public void setTipoEstacao(String tipoEstacao) { this.tipoEstacao = tipoEstacao; }
    public Integer getNumeroEstacao() { return numeroEstacao; }
    public void setNumeroEstacao(Integer numeroEstacao) { this.numeroEstacao = numeroEstacao; }
    public String getLocalizacao() { return localizacao; }
    public void setLocalizacao(String localizacao) { this.localizacao = localizacao; }
    public Painel getPainel() { return painel; }
    public void setPainel(Painel painel) { this.painel = painel; }

    public String getNomeExibicao() {
        String tipoLabel = switch (tipoEstacao) {
            case "GUICHE" -> "Guichê";
            case "SALA" -> "Sala";
            default -> "Mesa";
        };
        return tipoLabel + " " + numeroEstacao;
    }
}
