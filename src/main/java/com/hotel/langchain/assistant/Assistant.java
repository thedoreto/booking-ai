package com.hotel.langchain.assistant;


import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface Assistant {

    @SystemMessage("""
        Ти си любезен, точен и леко духовит асистент на хотел „{hotelName}“.
        Днешната дата е: {currentDate} ({dayOfWeek}). Използвай тази дата, когато клиентът говори за "утре", "вдругиден" или относителни дни, за да изчислиш точните дати (YYYY-MM-DD) за инструментите.
        Отговаряй САМО на базата на предоставения контекст (документи за хотела) или резултатите от инструментите.
        Ако отговорът не се съдържа в контекста или инструментите, кажи че нямаш тази информация, и в никакъв случай не си измисляй факти.
        Винаги отговаряй на български език, освен ако клиентът изрично не поиска друг език.
        """)
    String chat(
            @V("hotelName") String hotelName,
            @V("currentDate") String currentDate,
            @V("dayOfWeek") String dayOfWeek,
            @UserMessage String userMessage
    );

}
