package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TelegramWorkCardEditRecoveryTest {
    @Test
    void replacesPermanentlyUneditableCardAndRoutesFutureEditsToReplacement() throws Exception {
        HttpClient client = mock(HttpClient.class);
        Queue<HttpResponse<String>> responses = new ArrayDeque<>();
        responses.add(response(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message can't be edited\"}"));
        responses.add(response(200, "{\"ok\":true,\"result\":{\"message_id\":99}}"));
        responses.add(response(200, "{\"ok\":true,\"result\":{\"message_id\":99}}"));
        doAnswer(invocation -> responses.remove()).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        TelegramBotGateway gateway = new TelegramBotGateway("test-token", client, new ObjectMapper());

        assertDoesNotThrow(() -> gateway.editWorkCard("123", 42L, "first refresh"));
        assertDoesNotThrow(() -> gateway.editWorkCard("123", 42L, "second refresh"));
        verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void disablesRepeatedPermanentEditFailureInsteadOfCreatingInfiniteReplacementStorm() throws Exception {
        HttpClient client = mock(HttpClient.class);
        Queue<HttpResponse<String>> responses = new ArrayDeque<>();
        responses.add(response(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message can't be edited\"}"));
        responses.add(response(200, "{\"ok\":true,\"result\":{\"message_id\":99}}"));
        responses.add(response(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message can't be edited\"}"));
        doAnswer(invocation -> responses.remove()).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        TelegramBotGateway gateway = new TelegramBotGateway("test-token", client, new ObjectMapper());

        gateway.editWorkCard("123", 42L, "first refresh");
        IllegalStateException disabled = assertThrows(IllegalStateException.class,
                () -> gateway.editWorkCard("123", 42L, "second refresh"));
        assertEquals("telegram_work_card_edit_disabled_after_replacement", disabled.getMessage());
        assertDoesNotThrow(() -> gateway.editWorkCard("123", 42L, "third refresh"));
        verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void recognizesOnlyPermanentTelegramEditFailures() {
        assertTrue(TelegramBotGateway.permanentlyUneditableMessage(
                "telegram_send_failed:telegram_error=400:Bad Request: message can't be edited"));
        assertTrue(TelegramBotGateway.permanentlyUneditableMessage(
                "telegram_send_failed:telegram_error=400:Bad Request: message to edit not found"));
        assertEquals(false, TelegramBotGateway.permanentlyUneditableMessage(
                "telegram_send_failed:IOException:GOAWAY received"));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(status);
        org.mockito.Mockito.when(response.body()).thenReturn(body);
        return response;
    }
}