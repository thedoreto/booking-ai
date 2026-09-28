package com.hotel.admin;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.Console;
import java.util.Scanner;

// Прави BCrypt хеш на парола за admin.passwordHash в hotel_settings (виж ADMIN_USERS.md).
// Паролата се пише в конзолата, без да се вижда, и не остава в историята на командите.
public class AdminPasswordHash {

    public static void main(String[] args) {
        String password = readPassword();
        if (password == null || password.isBlank()) {
            System.err.println("Empty password");
            System.exit(1);
        }
        System.out.println(new BCryptPasswordEncoder().encode(password));
    }

    private static String readPassword() {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("Password: ");
            return chars == null ? null : new String(chars);
        }
        System.out.print("Password: ");
        Scanner scanner = new Scanner(System.in);
        return scanner.hasNextLine() ? scanner.nextLine() : null;
    }
}
