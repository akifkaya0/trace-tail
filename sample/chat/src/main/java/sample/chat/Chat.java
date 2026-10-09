package sample.chat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Caller;
import sample.tracing.Step;
import sample.tracing.Threads;

/**
 * The chat app: its members talk over STOMP. It signs them in against LDAP, sends the general room's
 * new messages to its subscribers every 5 seconds, mails invitations, and mails the members a digest
 * every 30 seconds. Logs through Logback.
 */
public final class Chat {

    private static final Logger log = LoggerFactory.getLogger(Chat.class);

    private static final Map<String, List<String>> ROOMS = Map.of(
            "general", List.of("Good morning", "Is the build green?"),
            "random", List.of("Lunch at noon?"));

    public static void main(String[] args) {
        Directory directory = new Directory();
        Mailer mailer = new Mailer();
        Stomp stomp = new Stomp();

        stomp.on("CONNECT", (user, body) -> {
            if (!directory.bind(user)) {
                throw new SecurityException("Unknown user " + user);
            }
            log.atInfo().addKeyValue("groups", directory.groups(user)).log("Signed in");
        });
        stomp.on("/app/history", (user, body) -> {
            String room = body.get("room");
            List<String> messages = ROOMS.get(room);
            if (messages == null) {
                throw new IllegalArgumentException("No room named " + room);
            }
            log.atDebug().addKeyValue("room", room).addKeyValue("messages", messages.size()).log("History read");
            stomp.send("MESSAGE", "/user/queue/history");
        });
        stomp.on("/app/invite", (user, body) -> mailer.send(directory.mail(body.get("invitee"))));

        directory.check();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Threads.named("scheduler"));
        scheduler.scheduleAtFixedRate(
                () -> Step.request(log, "JOB", Caller.NONE, "job", "digest")
                        .run(step -> directory.members("members").forEach(mailer::send)),
                10, 30, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> stomp.broadcast("MESSAGE", "/topic/general"), 5, 5, TimeUnit.SECONDS);
        new Member(stomp).start();
    }
}
