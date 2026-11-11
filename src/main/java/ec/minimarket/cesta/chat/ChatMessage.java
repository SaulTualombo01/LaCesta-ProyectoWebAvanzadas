package ec.minimarket.cesta.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "chat_message")
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String author;

    @Column(nullable = false, length = 400)
    private String content;

    @Column(nullable = false)
    private Instant sentAt;

    protected ChatMessage() {
    }

    ChatMessage(String author, String content) {
        this.author = author;
        this.content = content;
        this.sentAt = Instant.now();
    }

    ChatEntry toEntry() {
        return new ChatEntry(id, author, content, ChatEntry.ECUADOR_TIME.format(sentAt));
    }
}
