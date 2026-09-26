package com.hotel.langchain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortcutTest {

    @Test
    void toolActionIsRecognized() {
        Shortcut.Action tool = new Shortcut.Action();
        tool.setType(Shortcut.Action.TOOL);
        tool.setTool("showMyBookings");
        Shortcut.Action knowledge = new Shortcut.Action();
        knowledge.setType(Shortcut.Action.KNOWLEDGE);

        assertThat(tool.isTool()).isTrue();
        assertThat(knowledge.isTool()).isFalse();
    }

    @Test
    void missingIsActiveMeansActive() {
        Shortcut shortcut = new Shortcut();
        assertThat(shortcut.isActive()).isTrue();

        shortcut.setIsActive(false);
        assertThat(shortcut.isActive()).isFalse();
    }
}
