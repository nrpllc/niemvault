package gov.niemplatform.disclosure;

/**
 * A disclosure could not be recorded.
 *
 * <p>Never swallowed. This exception failing to propagate means data crossed an agency boundary with
 * no record of it, which is the one outcome the log exists to prevent.
 */
public class DisclosureLogException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DisclosureLogException(String message, Throwable cause) {
        super(message, cause);
    }

    public DisclosureLogException(String message) {
        super(message, null);
    }
}
