package com.metatron.workforce.adapter.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP transport endpoint. It is activated only when the adapter is explicitly wired. */
@RestController
@RequestMapping("/api/telegram")
@ConditionalOnBean(TelegramWorkplaceAdapter.class)
public final class TelegramWebhookController {
    private final TelegramWorkplaceAdapter adapter;

    public TelegramWebhookController(TelegramWorkplaceAdapter adapter) {
        this.adapter = adapter;
    }

    @PostMapping("/webhook")
    public TelegramWorkplaceAdapter.Result webhook(@RequestBody TelegramUpdate update) {
        return adapter.accept(update);
    }
}
