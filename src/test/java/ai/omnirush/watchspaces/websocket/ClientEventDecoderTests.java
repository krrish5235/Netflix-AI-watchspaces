package ai.omnirush.watchspaces.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientEventDecoderTests {
    private final ClientEventDecoder decoder = new ClientEventDecoder(new ObjectMapper());

    @Test
    void decodesTypedChatAndSeekEvents() {
        ClientEvent chat = decoder.decode("{\"type\":\"chat\",\"requestId\":\"m-1\",\"message\":\" hello \"}");
        ClientEvent seek = decoder.decode("{\"type\":\"seek\",\"requestId\":\"s-1\",\"positionSeconds\":42.5}");

        assertThat(chat).isEqualTo(new ClientEvent.Chat("m-1", "hello"));
        assertThat(seek).isEqualTo(new ClientEvent.Seek("s-1", 42.5));
    }

    @Test
    void rejectsUnsupportedAndMalformedEventsWithStableCodes() {
        assertThatThrownBy(() -> decoder.decode("{\"type\":\"rewind\",\"requestId\":\"r-1\"}"))
                .isInstanceOf(ProtocolException.class)
                .extracting("code", "requestId", "message")
                .containsExactly("UNSUPPORTED_EVENT", "r-1", "Unsupported event type: rewind");

        assertThatThrownBy(() -> decoder.decode("{\"type\":\"seek\",\"requestId\":\"r-2\",\"positionSeconds\":-1}"))
                .isInstanceOf(ProtocolException.class)
                .extracting("code", "requestId", "message")
                .containsExactly(
                        "INVALID_MESSAGE",
                        "r-2",
                        "Field 'positionSeconds' must be a non-negative number");
    }
}
