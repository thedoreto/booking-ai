package com.hotel.langchain.dto;

import java.util.List;

public class AiChatDto {
    public record MessageDto(String role, String content) {}

    public record ChatHistoryRequest(List<MessageDto> messages) {}

    public record ChatHistoryResponse(String reply) {}
}