package sample.chat;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Caller;
import sample.tracing.Step;
import sample.tracing.Work;

/** A stand-in for the LDAP directory, which writes no lines here. Each operation is an LDAP_OUT step. */
final class Directory {

    private static final Logger log = LoggerFactory.getLogger(Directory.class);

    private static final Map<String, String> MAIL = Map.of(
            "u-1001", "ada@example.com",
            "u-1002", "linus@example.com",
            "u-1003", "grace@example.com");
    private static final Map<String, List<String>> GROUPS = Map.of(
            "u-1001", List.of("members"),
            "u-1002", List.of("members", "moderators"),
            "u-1003", List.of("guests"));

    /** Binds as the app's own account at start-up, a request of its own, to check the connection. */
    void check() {
        Step.request(log, "LDAP_OUT", Caller.NONE, "op", "bind").run(step -> Work.take(10, 30));
    }

    /** Signs [user] in; false when the directory does not know them. */
    boolean bind(String user) {
        return op("bind", () -> MAIL.containsKey(user));
    }

    List<String> groups(String user) {
        return op("search", () -> GROUPS.getOrDefault(user, List.of()));
    }

    String mail(String user) {
        return op("search", () -> MAIL.get(user));
    }

    /** The mail addresses of the users in [group]. */
    List<String> members(String group) {
        return op("search", () -> GROUPS.keySet().stream().filter(u -> GROUPS.get(u).contains(group)).map(MAIL::get).toList());
    }

    private static <T> T op(String op, Supplier<T> answer) {
        return Step.start(log, "LDAP_OUT", "op", op).call(step -> {
            Work.take(5, 30);
            return answer.get();
        });
    }
}
