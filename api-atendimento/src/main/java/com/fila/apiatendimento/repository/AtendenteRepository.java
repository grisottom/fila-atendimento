package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.Atendente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AtendenteRepository extends JpaRepository<Atendente, Integer> {

    Optional<Atendente> findByUsernameAndAgenciaId(String username, String agenciaId);

    @Query("SELECT p.permissao FROM PermissaoAtendente p WHERE p.atendente.username = :username AND p.atendente.agenciaId = :agenciaId")
    List<String> findPermissoesByUsernameAndAgenciaId(@Param("username") String username, @Param("agenciaId") String agenciaId);

    List<Atendente> findByAgenciaId(String agenciaId);
}
