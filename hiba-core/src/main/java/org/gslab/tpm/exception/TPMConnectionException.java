package org.gslab.tpm.exception;

public class TPMConnectionException extends Exception {
    public TPMConnectionException(String message) {
        super(message);
    }

    public TPMConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
