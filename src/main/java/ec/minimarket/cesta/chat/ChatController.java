package ec.minimarket.cesta.chat;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class ChatController {
    private final ChatService chat;

    ChatController(ChatService chat) {
        this.chat = chat;
    }

    @GetMapping("/chat")
    String show(Model model) {
        model.addAttribute("messages", chat.history());
        return "chat/index";
    }
}
