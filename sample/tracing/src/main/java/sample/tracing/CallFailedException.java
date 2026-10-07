package sample.tracing;

/** A call to another app or an outside system failed. The request that made it answers with [status]. */
public final class CallFailedException extends RuntimeException {

    private final String target;
    private final int status;

    public CallFailedException(String target, int status, String message) {
        super(message);
        this.target = target;
        this.status = status;
    }

    public String target() {
        return target;
    }

    public int status() {
        return status;
    }
}
