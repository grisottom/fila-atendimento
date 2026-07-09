package com.fila.apiatendimento.entity;

import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "sala")
public class Sala {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "agencia_id")
    private String agenciaId;

    private String nome;
    private String localizacao;

    @ManyToMany
    @JoinTable(name = "sala_painel",
        joinColumns = @JoinColumn(name = "sala_id"),
        inverseJoinColumns = @JoinColumn(name = "painel_id"))
    private List<Painel> paineis;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getAgenciaId() { return agenciaId; }
    public void setAgenciaId(String agenciaId) { this.agenciaId = agenciaId; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getLocalizacao() { return localizacao; }
    public void setLocalizacao(String localizacao) { this.localizacao = localizacao; }
    public List<Painel> getPaineis() { return paineis; }
    public void setPaineis(List<Painel> paineis) { this.paineis = paineis; }
}
