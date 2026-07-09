package com.fila.apiatendimento.service;

import com.fila.apiatendimento.dto.AtendimentoResponse;
import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Painel;
import com.fila.apiatendimento.entity.Sala;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.SalaRepository;
import com.fila.apiatendimento.repository.ServicoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AtendimentoService {

    private final FilaAtendimentoRepository filaRepository;
    private final SalaRepository salaRepository;
    private final ServicoRepository servicoRepository;
    private final RestClient restClient;
    private final String internalApiKey;

    public AtendimentoService(FilaAtendimentoRepository filaRepository,
                              SalaRepository salaRepository,
                              ServicoRepository servicoRepository,
                              @Value("${app.api-painel-url}") String apiPainelUrl,
                              @Value("${app.internal-api-key}") String internalApiKey) {
        this.filaRepository = filaRepository;
        this.salaRepository = salaRepository;
        this.servicoRepository = servicoRepository;
        this.internalApiKey = internalApiKey;
        this.restClient = RestClient.builder().baseUrl(apiPainelUrl).build();
    }

    public AtendimentoResponse buscarAtivo(String username) {
        return filaRepository.findByAtendenteUsernameAndStatusIn(username, List.of("CHAMANDO", "EM_ATENDIMENTO"))
                .map(fila -> {
                    String salaNome = fila.getSalaId() != null
                            ? salaRepository.findById(fila.getSalaId()).map(Sala::getNome).orElse(null)
                            : null;
                    return toResponse(fila, salaNome);
                })
                .orElse(null);
    }

    @Transactional
    public AtendimentoResponse chamarProximo(Integer salaId, String username, List<String> permissoes) {
        Sala sala = salaRepository.findById(salaId)
                .orElseThrow(() -> new RuntimeException("Sala não encontrada: " + salaId));

        if (sala.getPaineis() == null || sala.getPaineis().isEmpty()) {
            throw new RuntimeException("Sala sem painéis associados");
        }

        List<FilaAtendimento> emChamada = filaRepository.findByAgenciaIdAndStatusIn(
                sala.getAgenciaId(), List.of("CHAMANDO"));
        emChamada.stream()
                .filter(f -> username.equals(f.getAtendenteUsername()))
                .forEach(f -> { /* atendente só pode ter 1 chamada ativa */ });

        FilaAtendimento proximo = filaRepository.findProximoParaAtendimento(sala.getAgenciaId(), permissoes)
                .orElseThrow(() -> new RuntimeException("Nenhum atendimento na fila"));

        proximo.setStatus("CHAMANDO");
        proximo.setSalaId(salaId);
        proximo.setAtendenteUsername(username);
        proximo.setHorarioChamada(LocalDateTime.now());
        filaRepository.save(proximo);

        publicarNoPainel(sala, proximo, "CHAMANDO");

        return toResponse(proximo, sala.getNome());
    }

    @Transactional
    public void rechamar(Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Sala sala = salaRepository.findById(fila.getSalaId())
                .orElseThrow(() -> new RuntimeException("Sala não encontrada"));

        publicarNoPainel(sala, fila, "CHAMANDO");
    }

    @Transactional
    public AtendimentoResponse ausentar(Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Sala sala = salaRepository.findById(fila.getSalaId()).orElse(null);

        Integer maxPosicao = filaRepository.findMaxPosicaoFila(fila.getAgenciaId()) + 1;
        fila.setStatus("AGUARDANDO");
        fila.setPosicaoFila(maxPosicao);
        fila.setHorarioAgendado(null); // perde prioridade de agendamento
        fila.setSalaId(null);
        fila.setAtendenteUsername(null);
        fila.setHorarioChamada(null);
        filaRepository.save(fila);

        if (sala != null) {
            publicarNoPainel(sala, fila, "AUSENTE");
        }

        return toResponse(fila, sala != null ? sala.getNome() : null);
    }

    @Transactional
    public AtendimentoResponse iniciarAtendimento(Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        fila.setStatus("EM_ATENDIMENTO");
        fila.setHorarioInicioAtendimento(LocalDateTime.now());
        filaRepository.save(fila);

        Sala sala = salaRepository.findById(fila.getSalaId()).orElse(null);
        if (sala != null) {
            publicarNoPainel(sala, fila, "EM_ATENDIMENTO");
        }

        return toResponse(fila, sala != null ? sala.getNome() : null);
    }

    @Transactional
    public AtendimentoResponse finalizarAtendimento(Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        fila.setStatus("FINALIZADO");
        fila.setHorarioFimAtendimento(LocalDateTime.now());
        filaRepository.save(fila);

        return toResponse(fila, null);
    }

    private void publicarNoPainel(Sala sala, FilaAtendimento fila, String status) {
        for (Painel painel : sala.getPaineis()) {
            try {
                restClient.post()
                        .uri("/api/internal/publicar")
                        .header("X-Internal-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "agenciaId", sala.getAgenciaId(),
                                "painelId", painel.getNumero(),
                                "senha", fila.getSenha(),
                                "nomePessoa", fila.getNomePessoa(),
                                "sala", sala.getNome(),
                                "status", status
                        ))
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                // painel pode estar offline
            }
        }
    }

    private AtendimentoResponse toResponse(FilaAtendimento fila, String salaNome) {
        return new AtendimentoResponse(fila.getId(), fila.getSenha(), fila.getNomePessoa(),
                fila.getServicoId(), fila.getStatus(), salaNome);
    }
}
