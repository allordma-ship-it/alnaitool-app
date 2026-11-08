DELETE FROM payment_events WHERE payment_id IN (SELECT id FROM payments WHERE provider = 'cash');
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_provider_check;
ALTER TABLE payments ADD CONSTRAINT payments_provider_check
    CHECK (provider IN ('stripe', 'simulated'));
