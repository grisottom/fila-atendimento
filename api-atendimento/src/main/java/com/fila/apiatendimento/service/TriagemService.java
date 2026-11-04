package com.fila.apiatendimento.service;

import com.fila.apiatendimento.dto.AgendamentoResponse;
import com.fila.apiatendimento.dto.AtendimentoResponse;
import com.fila.apiatendimento.dto.TriagemRequest;
import com.fila.apiatendimento.dto.TriagemResponse;
import com.fila.apiatendimento.entity.Agendamento;
import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Pessoa;
import com.fila.apiatendimento.repository.AgendamentoRepository;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.PessoaRepository;
import com.fila.apiatendimento.repository.ServicoRepository;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TriagemService {

    private static final Logger log = LoggerFactory.getLogger(TriagemService.class);
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    // FAULT INJECTION: contador para falhar nos 3 primeiros itens
    private static final java.util.concurrent.atomic.AtomicInteger faultCountTriagem = new java.util.concurrent.atomic.AtomicInteger(0);

    private final PessoaRepository pessoaRepository;
    private final AgendamentoRepository agendamentoRepository;
    private final FilaAtendimentoRepository filaRepository;
    private final ServicoRepository servicoRepository;
    private final JmsTemplate jmsQueueTemplate;

    // Intervalo típico (min) entre horários da agenda. A janela de retenção da
    // pré-triagem é 2 × este valor. Ver application.yml (atendimento.intervalo-agendamento-min).
    @Value("${atendimento.intervalo-agendamento-min:15}")
    private int intervaloAgendamentoMin;

    public TriagemService(PessoaRepository pessoaRepository,
                          AgendamentoRepository agendamentoRepository,
                          FilaAtendimentoRepository filaRepository,
                          ServicoRepository servicoRepository,
                          @Qualifier("jmsQueueTemplate") JmsTemplate jmsQueueTemplate) {
        this.pessoaRepository = pessoaRepository;
        this.agendamentoRepository = agendamentoRepository;
        this.filaRepository = filaRepository;
        this.servicoRepository = servicoRepository;
        this.jmsQueueTemplate = jmsQueueTemplate;
    }

    @Transactional
    public TriagemResponse recepcionar(TriagemRequest request) {
        Long cpf = Objects.requireNonNull(request.cpf(), "CPF não pode ser nulo");

        Pessoa pessoa = pessoaRepository.findById(cpf).orElse(null);
        String nomePessoa = pessoa != null ? pessoa.getNome() : request.nomePessoa();
        java.time.LocalDate dataNascimento = pessoa != null ? pessoa.getDataNascimento() : null;

        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime fimDia = inicioDia.plusDays(1);

        List<Agendamento> agendamentos = agendamentoRepository
                .findByCpfAndAgenciaIdAndDataHoraBetween(request.cpf(), request.agenciaId(), inicioDia, fimDia);

        Agendamento agendamento = agendamentos.stream()
                .filter(a -> a.getServicoId().equals(request.servicoId()))
                .findFirst().orElse(null);

        String senha = gerarSenha();
        Integer posicao = filaRepository.findMaxPosicaoFila(request.agenciaId()) + 1;

        LocalDateTime horarioAgendado = agendamento != null ? agendamento.getDataHora() : null;
        LocalDateTime agora = LocalDateTime.now();

        FilaAtendimento fila = new FilaAtendimento();
        fila.setAgenciaId(request.agenciaId());
        fila.setCpf(request.cpf());
        fila.setNomePessoa(nomePessoa);
        fila.setServicoId(request.servicoId());
        fila.setSenha(senha);
        fila.setHorarioAgendado(horarioAgendado);
        fila.setHorarioChegada(agora);
        fila.setPosicaoFila(posicao);

        // UUID de correlação gerado AQUI, antes do insert. É ele que viaja na
        // mensagem do broker, então não dependemos da PK (IDENTITY) para publicar.
        java.util.UUID triagemUuid = java.util.UUID.randomUUID();
        fila.setTriagemUuid(triagemUuid);

        // PRIORIDADE: combina idade (data de nascimento) com os marcadores manuais da
        // triagem (por lei / emergência). Vale a MAIOR prioridade. A categoria é
        // persistida; o nível JMS (9/8/7/6/4) é derivado dela + agendado na publicação.
        boolean porLei = Boolean.TRUE.equals(request.prioridadePorLei());
        boolean emergencia = Boolean.TRUE.equals(request.emergenciaMedica());
        String categoria = PrioridadeCalculator.categoria(dataNascimento, agora.toLocalDate(), porLei, emergencia);
        fila.setCategoriaPrioridade(categoria);

        boolean agendado = agendamento != null;
        int nivelPrioridade = PrioridadeCalculator.nivelJms(categoria, agendado);

        // PRÉ-TRIAGEM: um agendado que chega com muita antecedência (antecedência >
        // janela = 2 × intervalo) é RETIDO — recebe senha e é persistido como
        // AGENDADO_FUTURO, mas NÃO é publicado na fila agora. Evita que um antecipado
        // grosseiro fure a fila de quem tem horário mais próximo. O OutboxPublisher o
        // publica quando ele entra na janela (ou quando a agência está parada).
        // Exceção: prioridade ESPECIAL/EMERGENCIA nunca é retida (entra na hora).
        if (deveReterAgendado(horarioAgendado, agora, categoria)) {
            fila.setStatus(FilaAtendimento.STATUS_AGENDADO_FUTURO);
            fila.setPublicadoNoBroker(false);
            filaRepository.save(fila);
            log.info("Pré-triagem: agendado retido (AGENDADO_FUTURO) triagemUuid={} horario={} chegada={} (janela={}min)",
                    triagemUuid, horarioAgendado, agora, 2 * intervaloAgendamentoMin);

            if (agendamento != null) {
                agendamentoRepository.delete(agendamento);
            }
            return new TriagemResponse(senha, nomePessoa, request.servicoId(), horarioAgendado);
        }

        fila.setStatus(FilaAtendimento.STATUS_AGUARDANDO);

        // Outbox transacional com UMA única escrita: publicamos primeiro (o UUID já
        // existe em memória) e definimos a flag conforme o resultado. O save único
        // ao final persiste o estado final — não há insert seguido de update.
        //   - publish OK    -> publicadoNoBroker=true  (caminho feliz)
        //   - publish falha -> publicadoNoBroker=false; o OutboxPublisher republica.
        try {
            String servicoId = request.servicoId();
            String permissao = servicoId != null
                    ? servicoRepository.findById(servicoId).map(s -> s.getPermissaoExigida()).orElse("basica")
                    : "basica";

            publicarNaQueueAgencia(request.agenciaId(), triagemUuid, permissao, nivelPrioridade);
            fila.setPublicadoNoBroker(true);
        } catch (Exception e) {
            fila.setPublicadoNoBroker(false);
            log.warn("Falha ao publicar no broker (outbox vai republicar): {}", e.getMessage());
        }

        // Única persistência: grava a linha já com o estado final da flag.
        filaRepository.save(fila);

        if (agendamento != null) {
            agendamentoRepository.delete(agendamento);
        }

        // FAULT INJECTION (commit): desativada. Simulava o cenário de falha de commit
        // após o publish. O teste empírico mostrou que, com sessionTransacted=true no
        // jmsQueueTemplate, a sessão JMS é sincronizada com a transação e sofre rollback
        // junto — ou seja, NÃO gera mensagem órfã (publish e persistência caem juntos).
        // if (faultCountTriagem.incrementAndGet() <= 2) {
        //     log.warn("[FAULT] Simulando falha de COMMIT (item #{}) para triagemUuid={} — publish já feito, forçando rollback",
        //             faultCountTriagem.get(), triagemUuid);
        //     throw new RuntimeException("[FAULT] Falha de commit simulada (mensagem órfã no broker)");
        // }

        return new TriagemResponse(senha, nomePessoa, request.servicoId(),
                agendamento != null ? agendamento.getDataHora() : null);
    }

    /**
     * Decide se um agendado deve ser RETIDO (não publicado agora) pela pré-triagem.
     *
     * Retém quando a antecedência da chegada em relação ao horário agendado é maior
     * que a janela (2 × intervalo da agenda). Espontâneos (sem horário agendado) e
     * agendados dentro da janela ou já vencidos NÃO são retidos (publicam normal).
     *
     * Prioridade ESPECIAL/EMERGENCIA nunca é retida (entra na fila na hora, mesmo
     * muito adiantada).
     *
     * @param horarioAgendado horário do agendamento, ou null para espontâneo.
     * @param agora           instante da triagem (chegada).
     * @param categoria       categoria de prioridade do atendimento.
     */
    private boolean deveReterAgendado(LocalDateTime horarioAgendado, LocalDateTime agora, String categoria) {
        if (horarioAgendado == null) {
            return false; // espontâneo: publica normal
        }
        if (PrioridadeCalculator.ignoraRetencao(categoria)) {
            return false; // especial/emergência: nunca retém
        }
        LocalDateTime inicioJanela = horarioAgendado.minusMinutes(2L * intervaloAgendamentoMin);
        // Retém somente se a chegada é ANTES do início da janela (muito adiantada).
        // Dentro da janela ou com horário já vencido, agora >= inicioJanela → publica.
        return agora.isBefore(inicioJanela);
    }

    /**
     * Republica manualmente um atendimento AGUARDANDO na fila do broker.
     *
     * Rede de segurança operacional: se a mensagem de um atendimento se perdeu no
     * broker (ex.: reinício do broker sem armazenamento persistente), a linha
     * permanece AGUARDANDO no banco mas não há mensagem na fila para o atendente
     * consumir. Esta ação, acionada pelo triador, recoloca a mensagem na fila.
     *
     * NÃO altera o status nem qualquer outro dado — apenas republica. Se a mensagem
     * ainda estiver viva na fila (uso indevido), o chamarProximo é idempotente:
     * ao consumir a duplicata, verá o status já diferente de AGUARDANDO e a descarta.
     */
    @Transactional
    public void republicar(Integer id) {
        FilaAtendimento fila = filaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Atendimento não encontrado: " + id));

        // Aceita AGUARDANDO (rede de segurança comum) e FALHA_DLQ (item que esgotou
        // as tentativas automáticas de reprocessamento): o "Republicar" é a
        // intervenção humana que dá nova chance após a causa ter sido resolvida.
        if (!"AGUARDANDO".equals(fila.getStatus()) && !DlqReprocessor.STATUS_FALHA_DLQ.equals(fila.getStatus())) {
            throw new IllegalArgumentException(
                    "Só é possível republicar atendimentos AGUARDANDO ou " + DlqReprocessor.STATUS_FALHA_DLQ
                            + " (status atual: " + fila.getStatus() + ")");
        }

        // Intervenção manual zera o contador anti-loop: o item ganha um ciclo de
        // tentativas automáticas renovado. Também garante status AGUARDANDO para
        // itens que vinham de FALHA_DLQ.
        fila.setStatus("AGUARDANDO");
        fila.setTentativasDlq(0);
        filaRepository.save(fila);

        String permissao = fila.getServicoId() != null
                ? servicoRepository.findById(fila.getServicoId()).map(s -> s.getPermissaoExigida()).orElse("basica")
                : "basica";

        int prioridade = PrioridadeCalculator.nivelJms(
                fila.getCategoriaPrioridade(), fila.getHorarioAgendado() != null);
        publicarNaQueueAgencia(fila.getAgenciaId(), fila.getTriagemUuid(), permissao, prioridade);
        log.info("Republicação manual: filaId={} triagemUuid={} recolocado na fila da agência {} (prioridade={}, tentativas_dlq zerado)",
                fila.getId(), fila.getTriagemUuid(), fila.getAgenciaId(), prioridade);
    }

    void publicarNaQueueAgencia(String agenciaId, java.util.UUID triagemUuid,
                                            String permissao, int prioridade) {
        String queueAgencia = "agencia." + agenciaId + ".fila";
        String uuidStr = triagemUuid.toString();

        jmsQueueTemplate.send(queueAgencia, session -> {
            Message message = session.createTextMessage(uuidStr);
            message.setStringProperty("triagemUuid", uuidStr);
            message.setStringProperty("permissao", permissao);
            message.setJMSDeliveryMode(DeliveryMode.PERSISTENT);
            message.setJMSPriority(prioridade);
            return message;
        });

        log.info("Publicado na fila {}: triagemUuid={}, permissao={}, prioridade={}",
                queueAgencia, uuidStr, permissao, prioridade);
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

    public List<AtendimentoResponse> listarAtendimentosDoDiaEsperando(String agenciaId) {
        return filaRepository.findByAgenciaIdAndStatusIn(agenciaId,
                        List.of("AGUARDANDO", FilaAtendimento.STATUS_AGENDADO_FUTURO,
                                "CANCELADO", "AUSENTE", DlqReprocessor.STATUS_FALHA_DLQ))
                .stream()
                .map(f -> new AtendimentoResponse(f.getId(), f.getSenha(), f.getCpf(), f.getNomePessoa(),
                        f.getServicoId(), f.getStatus(), null, null, f.getHorarioChegada()))
                .toList();
    }

    @Transactional
    public TriagemResponse atualizarServico(Integer id, String servicoId) {
        FilaAtendimento fila = filaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Atendimento não encontrado: " + id));
        fila.setServicoId(servicoId);
        fila.setStatus("AGUARDANDO");
        fila.setPublicadoNoBroker(false);
        filaRepository.save(fila);

        return new TriagemResponse(fila.getSenha(), fila.getNomePessoa(), servicoId, fila.getHorarioAgendado());
    }

    /**
     * Busca a pessoa por CPF para a triagem (nome + data de nascimento + idade).
     * A idade alimenta o indicador read-only de prioridade por idade na tela.
     * Retorna null se não houver pessoa cadastrada com o CPF.
     */
    public com.fila.apiatendimento.dto.PessoaResponse buscarPessoa(Long cpf) {
        return pessoaRepository.findById(cpf)
                .map(p -> {
                    Integer idade = p.getDataNascimento() != null
                            ? java.time.Period.between(p.getDataNascimento(), LocalDate.now()).getYears()
                            : null;
                    return new com.fila.apiatendimento.dto.PessoaResponse(
                            p.getCpf(), p.getNome(), p.getDataNascimento(), idade);
                })
                .orElse(null);
    }
}
