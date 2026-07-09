package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.Servico;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ServicoRepository extends JpaRepository<Servico, String> {
    List<Servico> findByPermissaoExigidaIn(List<String> permissoes);
}
