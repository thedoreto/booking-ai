package com.hotel.langchain.tools;

import com.hotel.langchain.context.TenantContext;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// Бутон с action.type "tool": изпълнява tool от HotelTools по име, без Gemini.
// Името е като за модела – name от @Tool, иначе името на метода. Параметрите са null
// (както когато потребителят не е казал нищо), затова tool-ът трябва да ги приема незадължителни.
// Действие за UI (календар, списък с резервации) tool-ът записва сам в TenantContext.
@Component
public class ShortcutToolRunner {

    private final HotelTools hotelTools;
    private final Map<String, Method> toolsByName = new HashMap<>();

    public ShortcutToolRunner(HotelTools hotelTools) {
        this.hotelTools = hotelTools;
        for (Method method : HotelTools.class.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null) {
                toolsByName.put(tool.name().isBlank() ? method.getName() : tool.name(), method);
            }
        }
    }

    // Текстът, който tool-ът връща; празно – няма такъв tool
    public Optional<String> run(String toolName) {
        Method method = toolName != null ? toolsByName.get(toolName) : null;
        if (method == null) {
            System.err.println("Shortcut points to unknown tool: " + toolName);
            return Optional.empty();
        }
        try {
            Object result = method.invoke(hotelTools, new Object[method.getParameterCount()]);
            return Optional.of(result != null ? result.toString() : "");
        } catch (InvocationTargetException e) {
            // Tool-ове като getAvailableRoomsByDates искат действие в UI с изключение (OpenDatePickerException) –
            // действието вече е в TenantContext, контролерът го връща
            if (TenantContext.getUiAction() != null) {
                return Optional.of("");
            }
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Tool " + toolName + " failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Tool " + toolName + " is not accessible", e);
        }
    }
}
