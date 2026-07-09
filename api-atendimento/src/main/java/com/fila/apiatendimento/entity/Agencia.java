package com.fila.apiatendimento.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "agencia")
public class Agencia {
    @Id
    private String id;
    private String nome;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
}
