package com.hotel.langchain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

// Един превод – един и същ текст на всички езици. Колекцията е обща за всички хотели. В Mongo (translations):
//   { _id: <ObjectId>, key: "booking.error", texts: { bg: "Резервацията не беше направена: {error}", en: "..." } }
//   { _id: <ObjectId>, texts: { bg: "Единична стая", en: "Single room" } }
// С key – съобщение от кода на booking-ai ({име} се заменя със стойност); без key – текст, който идва отвън
// (от бекенда на хотела, етикет на бутон) и се търси по съдържанието си на който и да е език.
@Document(collection = "translations")
public class Translation {

    @Id
    private String id;
    private String key;
    // Код на език → текст
    private Map<String, String> texts;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public Map<String, String> getTexts() { return texts; }
    public void setTexts(Map<String, String> texts) { this.texts = texts; }
}
