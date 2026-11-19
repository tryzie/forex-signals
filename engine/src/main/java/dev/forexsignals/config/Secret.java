package dev.forexsignals.config;

/** A decrypted secret. toString() never reveals the value, so it is safe to log a record holding one. */
public record Secret(String value) {
    public boolean isSet() { return value != null && !value.isBlank(); }

    @Override
    public String toString() { return isSet() ? "Secret[set]" : "Secret[missing]"; }
}
