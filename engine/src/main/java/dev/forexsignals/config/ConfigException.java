package dev.forexsignals.config;

/** A configuration problem that must stop startup. Messages never contain secret values. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) { super(message); }
    public ConfigException(String message, Throwable cause) { super(message, cause); }
}
