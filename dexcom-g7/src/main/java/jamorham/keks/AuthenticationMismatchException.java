package jamorham.keks;

/** The peer's challenge did not match; no authenticated session was established. */
public final class AuthenticationMismatchException extends SecurityException {
    public AuthenticationMismatchException() {
        super("Mismatch - wait");
    }
}
