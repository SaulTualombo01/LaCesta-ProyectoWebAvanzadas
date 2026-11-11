package ec.minimarket.cesta.chat;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record ChatEntry(Long id, String author, String content, String sentAt) {
    static final DateTimeFormatter ECUADOR_TIME =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("America/Guayaquil"));
}
