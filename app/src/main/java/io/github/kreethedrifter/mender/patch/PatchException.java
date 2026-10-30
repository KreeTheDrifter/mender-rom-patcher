package io.github.kreethedrifter.mender.patch;

/** A patching problem, with a message written for the user. */
public class PatchException extends Exception {
    public PatchException(String message) {
        super(message);
    }
}
