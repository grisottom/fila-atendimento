package com.fila.apiatendimento.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "servico")
public class Servico {
    @Id
    private String id;
    private String nome;

    @Column(name = "permissao_exigida")
    private String permissaoExigida;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getPermissaoExigida() { return permissaoExigida; }
    public void setPermissaoExigida(String permissaoExigida) { this.permissaoExigida = permissaoExigida; }
}
