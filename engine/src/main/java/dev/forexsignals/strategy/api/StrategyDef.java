package dev.forexsignals.strategy.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a strategy class in dev.forexsignals.strategy.impl for discovery. The class needs a public no-arg constructor. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface StrategyDef {}
