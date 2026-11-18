-- Sealed copy of each member token so members can view it again. Additive and nullable.
ALTER TABLE members ADD COLUMN IF NOT EXISTS key_sealed TEXT;
