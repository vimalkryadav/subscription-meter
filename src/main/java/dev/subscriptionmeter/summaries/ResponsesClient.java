package dev.subscriptionmeter.summaries;

import java.io.IOException;

/** Minimal OpenAI Responses API call used for administrator-side summaries. */
public interface ResponsesClient {
    record Request(String apiKey, String model, String instructions, String input, int maxOutputTokens) {}
    record Result(String text, OpenAIApiBudget.Usage usage) {}

    /** The provider returned an HTTP error (nothing generated, so nothing billed). Message is safe to store. */
    final class Rejected extends Exception {
        private final int status;
        public Rejected(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    /** The response arrived but could not be used; usage (if known) was still billed. */
    final class Unusable extends Exception {
        private final OpenAIApiBudget.Usage usage;
        public Unusable(String message, OpenAIApiBudget.Usage usage) { super(message); this.usage = usage; }
        public OpenAIApiBudget.Usage usage() { return usage; }
    }

    Result create(Request request) throws Rejected, Unusable, IOException, InterruptedException;
}
