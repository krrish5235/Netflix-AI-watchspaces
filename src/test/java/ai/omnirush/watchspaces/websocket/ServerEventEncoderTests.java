package ai.omnirush.watchspaces.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerEventEncoderTests {
    @Test
    void encodesErrorsWithStableFieldOrderAndNoGeneratedFields() {
        ServerEventEncoder encoder = new ServerEventEncoder(new ObjectMapper());

        assertThat(encoder.encode(ServerEvent.error(
                "request-1", "HOST_REQUIRED", "Only the room host may control playback")))
                .isEqualTo("{\"type\":\"error\",\"requestId\":\"request-1\",\"code\":\"HOST_REQUIRED\",\"message\":\"Only the room host may control playback\"}");
    }
}
