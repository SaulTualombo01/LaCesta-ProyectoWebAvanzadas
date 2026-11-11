package ec.minimarket.cesta.chat;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
class ChatService {
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    private final ChatMessageRepository messages;

    ChatService(ChatMessageRepository messages) {
        this.messages = messages;
    }

    List<ChatEntry> history() {
        return messages.findTop50ByOrderByIdDesc().stream()
                .map(ChatMessage::toEntry)
                .sorted(Comparator.comparing(ChatEntry::id))
                .toList();
    }

    ChatEntry save(String author, String rawContent) {
        if (rawContent.length() > 400) {
            throw new IllegalArgumentException("El mensaje admite hasta 400 caracteres.");
        }
        String normalized = rawContent.replace("\r\n", "\n").replace('\r', '\n');
        String content = CONTROL_CHARACTERS.matcher(normalized).replaceAll("").strip();
        if (content.isBlank()) {
            throw new IllegalArgumentException("Escribe un mensaje antes de enviarlo.");
        }
        return messages.save(new ChatMessage(author, content)).toEntry();
    }
}
