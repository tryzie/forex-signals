package dev.forexsignals.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing of getUpdates answers and token scrubbing (no network; the token here is an obvious fake). */
class TelegramChatIdTest {

    @Test
    void chatsAreExtractedFromStartMessagesAndMembershipUpdates() throws Exception {
        var updates = new ObjectMapper().readTree("""
                {"ok":true,"result":[
                  {"update_id":1,"message":{"message_id":1,"text":"/start","chat":{"id":111222333,"type":"private","first_name":"Test","username":"tester"}}},
                  {"update_id":2,"message":{"message_id":2,"text":"hi","chat":{"id":111222333,"type":"private","first_name":"Test"}}},
                  {"update_id":3,"my_chat_member":{"chat":{"id":-1009,"type":"group","title":"Some group"}}}]}""");
        var chats = TelegramChatId.parseChats(updates);
        assertThat(chats).containsOnlyKeys(111222333L, -1009L);
        assertThat(chats.get(111222333L).type()).isEqualTo("private");
        assertThat(chats.get(111222333L).name()).isEqualTo("Test (@tester)");
        assertThat(chats.get(-1009L).name()).isEqualTo("Some group");
        assertThat(TelegramChatId.parseChats(new ObjectMapper().readTree("{\"ok\":true,\"result\":[]}"))).isEmpty();
    }

    @Test
    void tokenIsScrubbedFromAnyText() {
        String fake = "000000:FAKE-test-token";
        String msg = "Connection reset for https://api.telegram.org/bot" + fake + "/getUpdates";
        assertThat(TelegramChatId.scrub(msg, fake)).doesNotContain(fake).contains("<bot-token>");
        assertThat(TelegramChatId.scrub(null, fake)).isEmpty();
    }
}
