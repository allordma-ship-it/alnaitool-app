-- Cash settlement: passengers pay the driver in person (markets without card
-- acquiring, e.g. Syria). The payment row then tracks the seat reservation.
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_provider_check;
ALTER TABLE payments ADD CONSTRAINT payments_provider_check
    CHECK (provider IN ('stripe', 'simulated', 'cash'));
