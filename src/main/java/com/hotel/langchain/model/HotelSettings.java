package com.hotel.langchain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

// Настройките на хотела в booking-ai. В Mongo (hotel_settings), по един документ на хотел:
//   { _id: <ObjectId>, hotelId: "seven_stars", jwtPublicKey: <PEM или base64>,
//     languages: [{ code: "bg", name: "Български" }, { code: "en", name: "English" }], defaultLanguage: "bg",
//     admin: { email, name, passwordHash: <BCrypt> } }
// Данните се въвеждат на ръка – проверките (липсващи полета, грешни стойности) са в HotelKeys, HotelLanguages
// и AdminAuthService.
@Document(collection = "hotel_settings")
public class HotelSettings {

    @Id
    private String id;
    private String hotelId;
    private String jwtPublicKey;
    // Езиците на чата в реда на менюто
    private List<Language> languages;
    private String defaultLanguage;
    // Админът на хотела в админ панела на booking-ai – отделен от потребителите на сайта на хотела
    private Admin admin;

    public static class Language {
        private String code;
        // Името на самия език („English“, не „Английски“)
        private String name;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class Admin {
        // Един човек може да е админ на няколко хотела с един и същ имейл – входът е по хотел + имейл
        private String email;
        private String name;
        // BCrypt хеш на паролата (самата парола не се пази никъде)
        private String passwordHash;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPasswordHash() { return passwordHash; }
        public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHotelId() { return hotelId; }
    public void setHotelId(String hotelId) { this.hotelId = hotelId; }
    public String getJwtPublicKey() { return jwtPublicKey; }
    public void setJwtPublicKey(String jwtPublicKey) { this.jwtPublicKey = jwtPublicKey; }
    public List<Language> getLanguages() { return languages; }
    public void setLanguages(List<Language> languages) { this.languages = languages; }
    public String getDefaultLanguage() { return defaultLanguage; }
    public void setDefaultLanguage(String defaultLanguage) { this.defaultLanguage = defaultLanguage; }
    public Admin getAdmin() { return admin; }
    public void setAdmin(Admin admin) { this.admin = admin; }
}
