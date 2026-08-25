package com.hotel.langchain.content;

import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.langchain.context.TenantContext;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class HotelContentRetriever implements ContentRetriever {

    private final KnowledgeService knowledgeService;
    private static final Logger log = LoggerFactory.getLogger(HotelContentRetriever.class);

    public HotelContentRetriever(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public List<Content> retrieve(Query query) {
        try {
            // Взимаме динамичния hotelId, който дойде от UI през контролера
            String hotelId = TenantContext.getHotelId();

            // Ако колекцията ви зависи директно от hotelId или се образува с префикс:
            // String collectionName = "knowledge_" + hotelId;
            String collectionName = "knowledge_" + hotelId; // Или ако в UI ви подават директно името на колекцията

            List<KnowledgeDocument> documents = knowledgeService.findRelevant(query.text(), collectionName);

            if (documents == null || documents.isEmpty()) {
                return Collections.emptyList();
            }

            return documents.stream()
                    .map(doc -> Content.from(doc.getText()))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error while retrieving knowledge documents for query: " + query.text(), e);
            return Collections.emptyList();
        }
    }
}