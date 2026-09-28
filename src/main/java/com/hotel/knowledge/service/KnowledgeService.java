package com.hotel.knowledge.service;

import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.repository.KnowledgeRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class KnowledgeService {

    private final KnowledgeRepository knowledgeRepo;
    private final EmbeddingModel embeddingModel;

    public KnowledgeService(KnowledgeRepository knowledgeRepo,
                            EmbeddingModel embeddingModel) {
        this.knowledgeRepo = knowledgeRepo;
        this.embeddingModel = embeddingModel;
    }

    // Бутон със знание: текстовете на избраните документи от knowledge_<hotelId>, в реда на ids
    public List<String> textsByIds(String hotelId, List<ObjectId> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ObjectId> validIds = ids.stream().filter(Objects::nonNull).toList();
        if (validIds.isEmpty()) {
            return List.of();
        }
        // Документ без text се пропуска
        return knowledgeRepo.findByIds(hotelId, validIds).stream()
                .map(KnowledgeDocument::getText)
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }

    // RAG: най-близките по смисъл знания на хотела до въпроса (vector search в knowledge_<hotelId>)
    public List<KnowledgeDocument> findRelevant(String hotelId, String question) {
     //   testKnowledge();
        var embedding = embeddingModel.embed(question).content();

        List<Double> vector = embedding.vectorAsList()
                .stream()
                .map(Float::doubleValue)
                .toList();

        var result = knowledgeRepo.searchByVector(hotelId, vector);

        System.out.println("Question: " + question);
        System.out.println("Hotel: " + hotelId);
        System.out.println("Found: " + result.size());

        return result;
    }

    // only testing
    public void testKnowledge() {

        try {
            var embedding = embeddingModel
                    .embed("Хотелът се намира в Багдад, на ул. „Сезам“ №40. До хотела се стига по пътя към гората. След третия завой се вижда голяма скала. Хотелът се намира зад скалата. При входа гостите трябва да кажат „Сезам, отвори се!“, за да бъде отворен входът.")
                    .content();

            List<Double> vector = embedding.vectorAsList()
                    .stream()
                    .map(Float::doubleValue)
                    .toList();

            String vectorString = vector.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));

            System.out.println("[" + vectorString + "]");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
