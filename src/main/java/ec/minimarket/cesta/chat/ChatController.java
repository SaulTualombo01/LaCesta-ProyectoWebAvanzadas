package ec.minimarket.cesta.chat;

import ec.minimarket.cesta.account.CestaPrincipal;
import org.springframework.security.core.Authentication;
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
    String show(Model model, Authentication authentication) {
        String currentUser = "";
        if (authentication != null) {
            if (authentication.getPrincipal() instanceof CestaPrincipal principal) {
                currentUser = principal.displayName();
            } else {
                currentUser = authentication.getName();
            }
        }
        boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        model.addAttribute("messages", chat.history());
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("isAdmin", isAdmin);
        return "chat/index";
    }
}
