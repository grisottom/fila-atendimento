package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.Sala;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SalaRepository extends JpaRepository<Sala, Integer> {
    @EntityGraph(attributePaths = "paineis")
    List<Sala> findByAgenciaId(String agenciaId);

    @EntityGraph(attributePaths = "paineis")
    Optional<Sala> findById(Integer id);
}
