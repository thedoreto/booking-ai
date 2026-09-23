package com.hotel.knowledge.service;

import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.repository.KnowledgeRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public List<KnowledgeDocument> findRelevant(
            String question,
            String collectionName) {
     //   testKnowledge();
        var embedding = embeddingModel.embed(question).content();

        List<Double> vector = embedding.vectorAsList()
                .stream()
                .map(Float::doubleValue)
                .toList();

        var result = knowledgeRepo.searchByVector(
                vector,
                collectionName
        );

        System.out.println("Question: " + question);
        System.out.println("Collection: " + collectionName);
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
