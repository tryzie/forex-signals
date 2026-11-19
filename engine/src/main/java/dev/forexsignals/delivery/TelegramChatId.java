package dev.forexsignals.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forexsignals.config.AppConfig;
import dev.forexsignals.config.ConfigException;
import dev.forexsignals.config.Secret;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helper for Telegram setup ({@code --mode=chatid}): reads the bot token from the encrypted conf.xml and
 * prints the chat ID of whoever pressed Start on the bot, so the token is never typed into a shell.
 *
 * The token only ever lives in memory and in the request URL. It is never printed or logged, and every error
 * message is scrubbed of it before it is shown. Read-only: only getMe and getUpdates are called. Updates are
 * not acknowledged (no offset), so nothing is consumed.
 */
public final class TelegramChatId {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TelegramChatId() {}

    /** One chat that has written to the bot. */
    public record Chat(long id, String type, String name) {}

    public static int run(AppConfig cfg, Duration wait, PrintStream out) {
        Secret token = cfg.secret("config/telegram/botToken");
        if (!token.isSet()) throw new ConfigException("<telegram><botToken> is empty in " + cfg.file());
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        try {
            JsonNode me = call(http, token, "getMe", Duration.ofSeconds(20));
            out.println("bot: @" + me.path("result").path("username").asText("?") + " (token works; not shown)");

            Instant deadline = Instant.now().plus(wait);
            Map<Long, Chat> chats = new LinkedHashMap<>();
            do {
                long left = Math.max(1, Math.min(50, Duration.between(Instant.now(), deadline).toSeconds()));
                chats.putAll(parseChats(call(http, token, "getUpdates?timeout=" + left, Duration.ofSeconds(left + 15))));
                if (chats.isEmpty() && Instant.now().isBefore(deadline)) {
                    out.println("no messages yet: open the bot in Telegram, press Start (or send any message)... waiting");
                }
            } while (chats.isEmpty() && Instant.now().isBefore(deadline));

            if (chats.isEmpty()) {
                out.println("no chats found. Press Start on the bot, then run this again (Telegram keeps updates for 24 h).");
                return 1;
            }
            out.println("chats that wrote to the bot:");
            chats.values().forEach(c -> out.println("  chatId=" + c.id() + "  type=" + c.type() + "  name=" + c.name()));
            out.println("Put your private chat's id into ~/.forexsignals/conf.xml as <telegram chatId=\"...\">.");
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (Exception e) {
            out.println("telegram request failed: " + scrub(e.getClass().getSimpleName() + ": " + e.getMessage(), token.value()));
            return 1;
        }
    }

    private static JsonNode call(HttpClient http, Secret token, String method, Duration timeout) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token.value() + "/" + method))
                .timeout(timeout).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode body = JSON.readTree(resp.body());
        if (!body.path("ok").asBoolean(false)) {
            // Telegram's description never contains the token, but scrub anyway.
            throw new IllegalStateException(method.replaceAll("\\?.*", "") + " -> HTTP " + resp.statusCode() + " "
                    + scrub(body.path("description").asText(""), token.value()));
        }
        return body;
    }

    /** Distinct chats from a getUpdates answer (messages, edited messages, /start, bot membership changes). */
    public static Map<Long, Chat> parseChats(JsonNode updates) {
        Map<Long, Chat> out = new LinkedHashMap<>();
        for (JsonNode u : updates.path("result")) {
            for (String kind : new String[]{"message", "edited_message", "my_chat_member", "channel_post"}) {
                JsonNode chat = u.path(kind).path("chat");
                if (!chat.has("id")) continue;
                String name = chat.has("title") ? chat.path("title").asText()
                        : (chat.path("first_name").asText("") + (chat.has("username") ? " (@" + chat.path("username").asText() + ")" : "")).strip();
                out.putIfAbsent(chat.path("id").asLong(), new Chat(chat.path("id").asLong(), chat.path("type").asText("?"), name));
            }
        }
        return out;
    }

    /** Removes the token from any text before it is shown. */
    public static String scrub(String text, String token) {
        if (text == null) return "";
        if (token == null || token.isEmpty()) return text;
        return text.replace(token, "<bot-token>");
    }
}
