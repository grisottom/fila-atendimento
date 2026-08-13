package com.fila.apiatendimento.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "paineis_servicos")
public class PainelServico {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "painel_id", nullable = false)
    private Painel painel;

    @Column(name = "servico_id", nullable = false)
    private String servicoId;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Painel getPainel() { return painel; }
    public void setPainel(Painel painel) { this.painel = painel; }
    public String getServicoId() { return servicoId; }
    public void setServicoId(String servicoId) { this.servicoId = servicoId; }
}
