package com.fila.apiatendimento.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "permissoes_atendente")
public class PermissaoAtendente {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "atendente_id", nullable = false)
    private Atendente atendente;

    private String permissao;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Atendente getAtendente() { return atendente; }
    public void setAtendente(Atendente atendente) { this.atendente = atendente; }
    public String getPermissao() { return permissao; }
    public void setPermissao(String permissao) { this.permissao = permissao; }
}
