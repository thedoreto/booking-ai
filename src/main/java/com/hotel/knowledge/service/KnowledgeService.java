package com.hotel.knowledge.service;

import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.repository.KnowledgeRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class KnowledgeService {

    public static final int MAX_TEXT_LENGTH = 10_000;

    // Промяната от админ панела. Празните title, category, source и tags махат полето.
    public record KnowledgeChanges(String title, String category, List<String> tags, String source, String text) {}

    // Невалидна промяна: code – TEXT_REQUIRED или TEXT_TOO_LONG
    public static class InvalidKnowledgeException extends RuntimeException {
        private final String code;

        public InvalidKnowledgeException(String code) {
            super(code);
            this.code = code;
        }

        public String getCode() { return code; }
    }

    // Gemini не направи embedding – нищо не е записано
    public static class EmbeddingFailedException extends RuntimeException {
        public EmbeddingFailedException(Throwable cause) {
            super(cause);
        }
    }

    private final KnowledgeRepository knowledgeRepo;
    private final EmbeddingModel embeddingModel;

    public KnowledgeService(KnowledgeRepository knowledgeRepo,
                            EmbeddingModel embeddingModel) {
        this.knowledgeRepo = knowledgeRepo;
        this.embeddingModel = embeddingModel;
    }

    // Бутон със знание: текстовете на избраните документи от knowledge_<hotelId>, в реда на ids –
    // преводът на езика на чата, ако го има, иначе основният текст
    public List<String> textsByIds(String hotelId, List<ObjectId> ids, String language) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ObjectId> validIds = ids.stream().filter(Objects::nonNull).toList();
        if (validIds.isEmpty()) {
            return List.of();
        }
        // Документ без text се пропуска
        return knowledgeRepo.findByIds(hotelId, validIds).stream()
                .map(d -> textIn(d, language))
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }

    // Всички знания на хотела (админ панел), без embedding
    public List<KnowledgeDocument> findAll(String hotelId) {
        return knowledgeRepo.findAll(hotelId);
    }

    // Документите, които съществуват, в реда на ids (без embedding)
    public List<KnowledgeDocument> findByIds(String hotelId, List<ObjectId> ids) {
        return ids.isEmpty() ? List.of() : knowledgeRepo.findByIds(hotelId, ids);
    }

    // Един документ (без embedding); празно – невалиден id или няма такъв
    public Optional<KnowledgeDocument> findById(String hotelId, String id) {
        ObjectId objectId = toObjectId(id);
        return objectId == null ? Optional.empty() : knowledgeRepo.findByIds(hotelId, List.of(objectId)).stream().findFirst();
    }

    // Записва преводите (код на език → текст) – всеки задължителен и до MAX_TEXT_LENGTH; другите езици остават.
    // Кои езици са позволени, решава викащият (езиците на хотела). Празно – невалиден id или няма такъв документ.
    public Optional<KnowledgeDocument> setTranslations(String hotelId, String id, Map<String, String> translations) {
        Map<String, String> clean = new LinkedHashMap<>();
        translations.forEach((language, text) -> clean.put(language, validText(text)));
        ObjectId objectId = toObjectId(id);
        if (objectId == null) {
            return Optional.empty();
        }
        Optional<KnowledgeDocument> updated = knowledgeRepo.setTranslations(hotelId, objectId, clean);
        updated.ifPresent(d -> System.out.println("Knowledge translations saved: hotelId=" + hotelId + ", id=" + id
                + ", languages=" + clean.keySet()));
        return updated;
    }

    private static String textIn(KnowledgeDocument document, String language) {
        String translation = language != null && document.getTranslations() != null
                ? document.getTranslations().get(language) : null;
        return translation != null && !translation.isBlank() ? translation : document.getText();
    }

    // Промяна от админ панела. Нов embedding (Gemini) – само ако текстът е друг; ако Gemini не отговори, нищо не се записва.
    // Празно – невалиден id или няма такъв документ в knowledge_<hotelId>.
    public Optional<KnowledgeDocument> update(String hotelId, String id, KnowledgeChanges changes) {
        String text = validText(changes);
        ObjectId objectId = toObjectId(id);
        if (objectId == null) {
            return Optional.empty();
        }
        List<KnowledgeDocument> current = knowledgeRepo.findByIds(hotelId, List.of(objectId));
        if (current.isEmpty()) {
            return Optional.empty();
        }
        boolean textChanged = !text.equals(current.get(0).getText());
        List<Double> embedding = textChanged ? embedOrFail(hotelId, text) : null;
        Optional<KnowledgeDocument> updated = knowledgeRepo.update(hotelId, objectId, toDocument(changes, text), embedding);
        updated.ifPresent(d -> System.out.println("Knowledge updated: hotelId=" + hotelId + ", id=" + id
                + (textChanged ? ", new embedding" : "")));
        return updated;
    }

    // Ново знание от админ панела. Embedding-ът (Gemini) е задължителен – ако Gemini не отговори, нищо не се записва.
    public KnowledgeDocument create(String hotelId, KnowledgeChanges changes) {
        String text = validText(changes);
        KnowledgeDocument document = toDocument(changes, text);
        document.setEmbedding(embedOrFail(hotelId, text));
        KnowledgeDocument created = knowledgeRepo.insert(hotelId, document);
        System.out.println("Knowledge created: hotelId=" + hotelId + ", id=" + created.getId());
        return created;
    }

    // Изтрива знанието (дали го ползва бутон – проверява се преди това). false – невалиден id или няма такъв документ.
    public boolean delete(String hotelId, String id) {
        ObjectId objectId = toObjectId(id);
        boolean deleted = objectId != null && knowledgeRepo.delete(hotelId, objectId);
        if (deleted) {
            System.out.println("Knowledge deleted: hotelId=" + hotelId + ", id=" + id);
        }
        return deleted;
    }

    private static String validText(KnowledgeChanges changes) {
        return validText(changes.text());
    }

    private static String validText(String value) {
        String text = trimToNull(value);
        if (text == null) {
            throw new InvalidKnowledgeException("TEXT_REQUIRED");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidKnowledgeException("TEXT_TOO_LONG");
        }
        return text;
    }

    private static ObjectId toObjectId(String id) {
        return id != null && ObjectId.isValid(id) ? new ObjectId(id) : null;
    }

    private static KnowledgeDocument toDocument(KnowledgeChanges changes, String text) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(trimToNull(changes.title()));
        document.setCategory(trimToNull(changes.category()));
        document.setTags(cleanTags(changes.tags()));
        document.setSource(trimToNull(changes.source()));
        document.setText(text);
        return document;
    }

    private List<Double> embedOrFail(String hotelId, String text) {
        try {
            return embed(text);
        } catch (RuntimeException e) {
            System.err.println("Embedding failed for hotelId=" + hotelId + ": " + e);
            throw new EmbeddingFailedException(e);
        }
    }

    // RAG: най-близките по смисъл знания на хотела до въпроса (vector search в knowledge_<hotelId>)
    public List<KnowledgeDocument> findRelevant(String hotelId, String question) {
     //   testKnowledge();
        var result = knowledgeRepo.searchByVector(hotelId, embed(question));

        System.out.println("Question: " + question);
        System.out.println("Hotel: " + hotelId);
        System.out.println("Found: " + result.size());

        return result;
    }

    // Векторът на текста (gemini-embedding-001) – с него са и знанията, и въпросите
    private List<Double> embed(String text) {
        return embeddingModel.embed(text).content().vectorAsList().stream()
                .map(Float::doubleValue)
                .toList();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // Без празни и повторени, в реда на въвеждане
    private static List<String> cleanTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        List<String> clean = tags.stream()
                .map(KnowledgeService::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream().toList();
        return clean.isEmpty() ? null : clean;
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
