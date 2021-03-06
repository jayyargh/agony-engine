package com.agonyengine.resource;

import com.agonyengine.model.actor.Actor;
import com.agonyengine.model.actor.GameMap;
import com.agonyengine.model.actor.PlayerActorTemplate;
import com.agonyengine.model.interpret.QuotedString;
import com.agonyengine.model.interpret.Verb;
import com.agonyengine.model.stomp.GameOutput;
import com.agonyengine.model.stomp.UserInput;
import com.agonyengine.repository.ActorRepository;
import com.agonyengine.repository.GameMapRepository;
import com.agonyengine.repository.PlayerActorTemplateRepository;
import com.agonyengine.repository.VerbRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
public class WebSocketResource {
    static final String SPRING_SESSION_ID_KEY = "SPRING.SESSION.ID";

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketResource.class);

    private String applicationVersion;
    private Date applicationBootDate;
    private UUID defaultMapId;
    private ApplicationContext applicationContext;
    private InputTokenizer inputTokenizer;
    private GameMapRepository gameMapRepository;
    private SessionRepository sessionRepository;
    private ActorRepository actorRepository;
    private PlayerActorTemplateRepository playerActorTemplateRepository;
    private VerbRepository verbRepository;
    private List<String> greeting;

    @Inject
    public WebSocketResource(
        String applicationVersion,
        Date applicationBootDate,
        UUID defaultMapId,
        ApplicationContext applicationContext,
        InputTokenizer inputTokenizer,
        GameMapRepository gameMapRepository,
        SessionRepository sessionRepository,
        ActorRepository actorRepository,
        PlayerActorTemplateRepository playerActorTemplateRepository,
        VerbRepository verbRepository) {

        this.applicationVersion = applicationVersion;
        this.applicationBootDate = applicationBootDate;
        this.defaultMapId = defaultMapId;
        this.applicationContext = applicationContext;
        this.inputTokenizer = inputTokenizer;
        this.gameMapRepository = gameMapRepository;
        this.sessionRepository = sessionRepository;
        this.actorRepository = actorRepository;
        this.playerActorTemplateRepository = playerActorTemplateRepository;
        this.verbRepository = verbRepository;

        InputStream greetingInputStream = WebSocketResource.class.getResourceAsStream("/greeting.txt");
        BufferedReader greetingReader = new BufferedReader(new InputStreamReader(greetingInputStream));

        greeting = greetingReader.lines().collect(Collectors.toList());
    }

    @SubscribeMapping("/queue/output")
    public GameOutput onSubscribe(Principal principal, Message<byte[]> message) {
        Session session = getSpringSession(message);
        GameOutput output = new GameOutput();

        greeting.forEach(line -> {
            if (line.startsWith("*")) {
                output.append(line.substring(1));
            } else {
                output.append(line.replace(" ", "&nbsp;"));
            }
        });

        output.append("[dwhite]Server status:");
        output.append("[dwhite]&nbsp;&nbsp;Version: [white]v" + applicationVersion);
        output.append("[dwhite]&nbsp;&nbsp;Last boot: [white]" + applicationBootDate);
        output.append("[dyellow]A relentless grinding rattles your very soul as [red]The Agony Engine " +
            "[dyellow]carries out its barbarous task...");
        output.append("");
        output.append("[dwhite]> ");

        Actor actor = actorRepository.findBySessionUsernameAndSessionId(principal.getName(), getStompSessionId(message));

        if (actor == null) {
            PlayerActorTemplate pat = playerActorTemplateRepository
                .findById(UUID.fromString(session.getAttribute("actor_template")))
                .orElse(null);

            GameMap defaultMap = gameMapRepository.getOne(defaultMapId);

            actor = new Actor();

            actor.setName(pat.getGivenName());
            actor.setSessionUsername(principal.getName());
            actor.setSessionId(getStompSessionId(message));
            actor.setGameMap(defaultMap);
            actor.setX(0);
            actor.setY(0);

            actor = actorRepository.save(actor);

            LOGGER.info("{} has connected to the game", actor.getName());
        } else {
            LOGGER.info("{} has reconnected", actor.getName());
        }

        return output;
    }

    @MessageMapping("/input")
    @SendToUser(value = "/queue/output", broadcast = false)
    public GameOutput onInput(Principal principal, UserInput input, Message<byte[]> message) {
        Actor actor = actorRepository.findBySessionUsernameAndSessionId(principal.getName(), getStompSessionId(message));
        GameOutput output = new GameOutput();
        List<List<String>> sentences = inputTokenizer.tokenize(input.getInput());

        for (List<String> tokens : sentences) {
            try {
                String verbToken = tokens.get(0);
                Verb verb = verbRepository
                    .findAll(Sort.by(Sort.Direction.ASC, "priority", "name"))
                    .stream()
                    .filter(v -> v.getName().toUpperCase().startsWith(verbToken))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown verb: " + verbToken));

                if (verb.isQuoting()) {
                    QuotedString quoted = new QuotedString(removeFirstWord(input.getInput()));
                    Object verbBean = applicationContext.getBean(verb.getBean());
                    Method verbMethod = ReflectionUtils.findMethod(verbBean.getClass(), "invoke", Actor.class, GameOutput.class, QuotedString.class);
                    ReflectionUtils.invokeMethod(verbMethod, verbBean, actor, output, quoted);

                    break;
                } else {
                    Object verbBean = applicationContext.getBean(verb.getBean());
                    Method verbMethod = ReflectionUtils.findMethod(verbBean.getClass(), "invoke", Actor.class, GameOutput.class);
                    ReflectionUtils.invokeMethod(verbMethod, verbBean, actor, output);
                }
            } catch (IllegalArgumentException | BeansException e) {
                LOGGER.error(e.getMessage());

                output.append("[dwhite]" + e.getMessage());
            }
        }

        output
            .append("")
            .append("[dwhite]> ");

        return output;
    }

    private Session getSpringSession(Message<byte[]> message) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.wrap(message);
        String sessionId = (String)headerAccessor.getSessionAttributes().get(SPRING_SESSION_ID_KEY);

        return sessionRepository.findById(sessionId);
    }

    private String getStompSessionId(Message<byte[]> message) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.wrap(message);

        return headerAccessor.getSessionId();
    }

    private String removeFirstWord(String in) {
        if (in.indexOf(' ') != -1) {
            int i = in.indexOf(' ');

            while (i < in.length() &&  in.charAt(i) == ' ') {
                i++;
            }

            return in.substring(i);
        }

        return "";
    }
}
