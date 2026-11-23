-- Routing strategy: 'claude_first' (use Claude until it refuses) or 'balanced' (new sessions go to
-- whichever of Claude and Codex has more quota left). The OpenAI API key tier is last in both.
ALTER TABLE routing_settings ADD COLUMN IF NOT EXISTS strategy VARCHAR(20) NOT NULL DEFAULT 'claude_first';
