package sample.tracing;

/** The ids a request arrives with: its trace, the step that sent it and the user. Null where none came. */
public record Caller(String trace, String parent, String user) {

    /** A request that starts here, such as a scheduled job. */
    public static final Caller NONE = new Caller(null, null, null);
}
