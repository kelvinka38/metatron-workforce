package com.metatron.workforce.interaction.channel;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Rejects duplicate and delayed Telegram webhook update IDs.
 *
 * Telegram update_id is globally increasing for a bot, therefore a strictly
 * increasing acceptance rule prevents webhook retries from re-entering the
 * interaction pipeline.
 */
public final class TelegramUpdateDeduplicator {

    private final AtomicLong highestAcceptedUpdateId = new AtomicLong(-1L);

    public boolean accept(long updateId) {
        if (updateId < 0) {
            throw new IllegalArgumentException("telegram_update_id_invalid");
        }

        while (true) {
            long current = highestAcceptedUpdateId.get();

            if (updateId <= current) {
                return false;
            }

            if (highestAcceptedUpdateId.compareAndSet(current, updateId)) {
                return true;
            }
        }
    }

    public long highestAcceptedUpdateId() {
        return highestAcceptedUpdateId.get();
    }
}
