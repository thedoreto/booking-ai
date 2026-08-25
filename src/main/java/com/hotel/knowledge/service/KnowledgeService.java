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
                    .embed("Хотелът има седем(7) звезди.")
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
