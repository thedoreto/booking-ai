package com.hotel.langchain.log;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatFlowTest {

    @Test
    void keepsValidFlowIdAndStartsNewOtherwise() {
        String flowId = UUID.randomUUID().toString();

        assertThat(ChatFlow.idOrNew(flowId)).isEqualTo(flowId);
        assertThat(ChatFlow.idOrNew(" " + flowId + " ")).isEqualTo(flowId);
        assertThat(ChatFlow.idOrNew(null)).isNotBlank().isNotEqualTo(ChatFlow.idOrNew(null));
        assertThat(ChatFlow.idOrNew("not-a-uuid")).isNotEqualTo("not-a-uuid");
        UUID.fromString(ChatFlow.idOrNew("not-a-uuid"));
    }

    @Test
    void mapsStepOutcomeToFlowStatus() {
        assertThat(ChatFlow.searchStatus(ChatLogEntry.OK)).isEqualTo(ChatFlow.ROOMS_SHOWN);
        assertThat(ChatFlow.searchStatus(ChatLogEntry.NO_RESULT)).isEqualTo(ChatFlow.NO_ROOMS);
        assertThat(ChatFlow.searchStatus(ChatLogEntry.REJECTED)).isEqualTo(ChatFlow.REJECTED);
        assertThat(ChatFlow.searchStatus(ChatLogEntry.ERROR)).isEqualTo(ChatFlow.ERROR);
        assertThat(ChatFlow.bookingStatus(ChatLogEntry.OK)).isEqualTo(ChatFlow.BOOKED);
        assertThat(ChatFlow.bookingStatus(ChatLogEntry.REJECTED)).isEqualTo(ChatFlow.BOOKING_FAILED);
        assertThat(ChatFlow.cancelStatus(ChatLogEntry.OK)).isEqualTo(ChatFlow.CANCELED);
        assertThat(ChatFlow.cancelStatus(ChatLogEntry.ERROR)).isEqualTo(ChatFlow.CANCEL_FAILED);
    }
}
