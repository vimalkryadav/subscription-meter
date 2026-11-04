package dev.subscriptionmeter.audit;

/**
 * Schema and retention changes belong to the serialized deployment migration job.
 * Kept as a compatibility marker; serving instances never mutate schema or recover global pending rows.
 */
public final class TimescaleSetup { private TimescaleSetup() {} }
