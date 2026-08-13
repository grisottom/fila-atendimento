package com.fila.apiatendimento.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "atendente")
public class Atendente {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String username;

    @Column(name = "agencia_id")
    private String agenciaId;

    @OneToMany(mappedBy = "atendente", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PermissaoAtendente> permissoes = new ArrayList<>();

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getAgenciaId() { return agenciaId; }
    public void setAgenciaId(String agenciaId) { this.agenciaId = agenciaId; }
    public List<PermissaoAtendente> getPermissoes() { return permissoes; }
    public void setPermissoes(List<PermissaoAtendente> permissoes) { this.permissoes = permissoes; }
}
