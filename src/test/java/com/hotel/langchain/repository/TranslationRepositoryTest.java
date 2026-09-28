package com.hotel.langchain.repository;

import com.hotel.langchain.model.Translation;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TranslationRepositoryTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final TranslationRepository repository = new TranslationRepository(mongoTemplate);

    @Test
    void commonAndHotelTranslationsAreInTheirOwnCollections() {
        repository.findCommon();
        repository.findByHotelId("40_robbers");

        verify(mongoTemplate).findAll(Translation.class, "translations");
        verify(mongoTemplate).findAll(Translation.class, "translations_40_robbers");
    }
}
