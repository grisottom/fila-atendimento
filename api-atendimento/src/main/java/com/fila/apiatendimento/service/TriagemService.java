package com.fila.apiatendimento.service;

import com.fila.apiatendimento.dto.AgendamentoResponse;
import com.fila.apiatendimento.dto.TriagemRequest;
import com.fila.apiatendimento.dto.TriagemResponse;
import com.fila.apiatendimento.entity.Agendamento;
import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Pessoa;
import com.fila.apiatendimento.repository.AgendamentoRepository;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.PessoaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TriagemService {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final PessoaRepository pessoaRepository;
    private final AgendamentoRepository agendamentoRepository;
    private final FilaAtendimentoRepository filaRepository;

    public TriagemService(PessoaRepository pessoaRepository,
                          AgendamentoRepository agendamentoRepository,
                          FilaAtendimentoRepository filaRepository) {
        this.pessoaRepository = pessoaRepository;
        this.agendamentoRepository = agendamentoRepository;
        this.filaRepository = filaRepository;
    }

    @Transactional
    public TriagemResponse recepcionar(TriagemRequest request) {
        Long cpf = Objects.requireNonNull(request.cpf(), "CPF não pode ser nulo");

        String nomePessoa = pessoaRepository.findById(cpf)
                .map(Pessoa::getNome)
                .orElse(request.nomePessoa());

        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime fimDia = inicioDia.plusDays(1);

        List<Agendamento> agendamentos = agendamentoRepository
                .findByCpfAndAgenciaIdAndDataHoraBetween(request.cpf(), request.agenciaId(), inicioDia, fimDia);

        Agendamento agendamento = agendamentos.stream()
                .filter(a -> a.getServicoId().equals(request.servicoId()))
                .findFirst().orElse(null);

        String senha = gerarSenha();
        Integer posicao = filaRepository.findMaxPosicaoFila(request.agenciaId()) + 1;

        FilaAtendimento fila = new FilaAtendimento();
        fila.setAgenciaId(request.agenciaId());
        fila.setCpf(request.cpf());
        fila.setNomePessoa(nomePessoa);
        fila.setServicoId(request.servicoId());
        fila.setSenha(senha);
        fila.setHorarioAgendado(agendamento != null ? agendamento.getDataHora() : null);
        fila.setHorarioChegada(LocalDateTime.now());
        fila.setStatus("AGUARDANDO");
        fila.setPosicaoFila(posicao);

        filaRepository.save(fila);

        if (agendamento != null) {
            agendamentoRepository.delete(agendamento);
        }

        return new TriagemResponse(senha, nomePessoa, request.servicoId(),
                agendamento != null ? agendamento.getDataHora() : null);
    }

    private String gerarSenha() {
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(CHARS.charAt(ThreadLocalRandom.current().nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    public Page<AgendamentoResponse> listarAgendamentosDoDia(String agenciaId, int page, int size) {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime fimDia = inicioDia.plusDays(1);

        return agendamentoRepository.findByAgenciaIdAndDataHoraBetweenOrderByDataHoraAsc(
                agenciaId, inicioDia, fimDia, PageRequest.of(page, size))
                .map(a -> {
                    String nome = pessoaRepository.findById(a.getCpf())
                            .map(Pessoa::getNome).orElse("Desconhecido");
                    return new AgendamentoResponse(a.getCpf(), nome, a.getServicoId(), a.getDataHora());
                });
    }
}
