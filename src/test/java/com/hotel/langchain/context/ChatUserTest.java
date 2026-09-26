package com.hotel.langchain.context;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ChatUserTest {

    @Test
    void noBearerTokenMeansGuest() {
        assertThat(ChatUser.fromAuthorization(null)).isNull();
        assertThat(ChatUser.fromAuthorization("")).isNull();
        assertThat(ChatUser.fromAuthorization("Basic abc")).isNull();
        assertThat(ChatUser.fromAuthorization("Bearer ")).isNull();
    }

    @Test
    void readsUserIdForLogsAndKeepsTheToken() {
        String token = jwt("{\"userId\":\"user-1\"}");

        ChatUser user = ChatUser.fromAuthorization("Bearer " + token);

        assertThat(user.id()).isEqualTo("user-1");
        assertThat(user.token()).isEqualTo(token);
    }

    @Test
    void unreadableTokenIsStillAUserWithoutId() {
        // Отказът идва от booking-system, когато токенът стигне до него
        ChatUser user = ChatUser.fromAuthorization("Bearer not-a-jwt");

        assertThat(user.id()).isNull();
        assertThat(user.token()).isEqualTo("not-a-jwt");
    }

    @Test
    void memoryKeyDependsOnTheWholeTokenNotOnUserId() {
        ChatUser real = ChatUser.fromAuthorization("Bearer " + jwt("{\"userId\":\"user-1\"}") + "real-signature");
        ChatUser forged = ChatUser.fromAuthorization("Bearer " + jwt("{\"userId\":\"user-1\"}") + "forged");

        assertThat(forged.id()).isEqualTo(real.id());
        assertThat(forged.memoryKey()).isNotEqualTo(real.memoryKey());
    }

    private static String jwt(String claims) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".";
    }
}
