package ec.minimarket.cesta.account;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AccountController {
    private static final String CONFIRM = "/confirmar";
    private static final String RESET = "/restablecer";
    private final SupabaseAuth auth;
    private final EmailFlow flow;
    private final SessionRegistry sessions;
    private final ApplicationEventPublisher events;

    AccountController(SupabaseAuth auth, EmailFlow flow, SessionRegistry sessions, ApplicationEventPublisher events) {
        this.auth = auth;
        this.flow = flow;
        this.sessions = sessions;
        this.events = events;
    }

    @GetMapping("/ingresar")
    String login() {
        return "auth/login";
    }

    @GetMapping("/registro")
    String register(Model model) {
        model.addAttribute("form", new RegistrationForm("", "", ""));
        return "auth/register";
    }

    @PostMapping("/registro")
    String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult errors,
                    HttpSession session, Model model) {
        validatePasswordSize(form.password(), errors);
        if (errors.hasErrors()) {
            return "auth/register";
        }
        String email = AccountAuthentication.normalize(form.email());
        var pending = flow.start(session, CONFIRM, email);
        try {
            auth.register(form.name().strip(), email, form.password(), pending.challenge(), flow.redirect(CONFIRM));
            return "redirect:/ingresar?registro";
        } catch (SupabaseAuth.Rejected | SupabaseAuth.Unavailable failure) {
            flow.clear(session, CONFIRM);
            model.addAttribute("authError", "No se pudo iniciar el registro. Revisa los datos e inténtalo de nuevo.");
            return "auth/register";
        }
    }

    @GetMapping(CONFIRM)
    String confirm(@RequestParam(required = false) String code, HttpSession session) {
        var pending = flow.read(session, CONFIRM, code);
        if (pending == null) return "redirect:/ingresar?enlace";
        flow.clear(session, CONFIRM);
        try {
            var confirmation = auth.exchange(code, pending.verifier());
            try {
                return pending.matches(confirmation) ? "redirect:/ingresar?confirmada" : "redirect:/ingresar?enlace";
            } finally {
                auth.revoke(confirmation, "local");
            }
        } catch (SupabaseAuth.Rejected | SupabaseAuth.Unavailable failure) {
            return "redirect:/ingresar?enlace";
        }
    }

    @GetMapping("/recuperar")
    String forgot(Model model) {
        model.addAttribute("form", new RecoveryForm(""));
        return "auth/forgot";
    }

    @PostMapping("/recuperar")
    String forgot(@Valid @ModelAttribute("form") RecoveryForm form, BindingResult errors,
                  HttpSession session, Model model) {
        if (errors.hasErrors()) {
            return "auth/forgot";
        }
        String email = AccountAuthentication.normalize(form.email());
        var pending = flow.start(session, RESET, email);
        try {
            auth.recover(email, pending.challenge(), flow.redirect(RESET));
        } catch (SupabaseAuth.Rejected failure) {
            flow.clear(session, RESET);
        } catch (SupabaseAuth.Unavailable failure) {
            flow.clear(session, RESET);
            model.addAttribute("authError", "El servicio de recuperación no está disponible. Inténtalo más tarde.");
            return "auth/forgot";
        }
        model.addAttribute("sent", true);
        return "auth/forgot";
    }

    @GetMapping(RESET)
    String reset(@RequestParam(required = false) String code, HttpSession session, Model model) {
        model.addAttribute("form", new ResetForm(code, ""));
        model.addAttribute("invalidToken", flow.read(session, RESET, code) == null);
        return "auth/reset";
    }

    @PostMapping(RESET)
    String reset(@Valid @ModelAttribute("form") ResetForm form, BindingResult errors,
                 HttpSession session, Model model) {
        validatePasswordSize(form.password(), errors);
        var pending = flow.read(session, RESET, form.code());
        model.addAttribute("invalidToken", pending == null);
        if (errors.hasErrors() || pending == null) {
            return "auth/reset";
        }
        flow.clear(session, RESET);
        try {
            var verified = auth.exchange(form.code(), pending.verifier());
            boolean changed = false;
            try {
                if (!pending.matches(verified)) {
                    model.addAttribute("invalidToken", true);
                    return "auth/reset";
                }
                auth.updatePassword(verified, form.password());
                changed = true;
                var id = verified.user().id();
                sessions.getAllSessions(new CestaPrincipal(id, ""), false).forEach(SessionInformation::expireNow);
                events.publishEvent(new AccountSessionsExpired(id));
                return "redirect:/ingresar?restablecida";
            } finally {
                auth.revoke(verified, changed ? "global" : "local");
            }
        } catch (SupabaseAuth.Rejected | SupabaseAuth.Unavailable failure) {
            model.addAttribute("invalidToken", true);
            return "auth/reset";
        }
    }

    private static void validatePasswordSize(String password, BindingResult errors) {
        if (!errors.hasFieldErrors("password") && AccountAuthentication.exceedsBcryptLimit(password)) {
            errors.rejectValue("password", "size", "Usa una contraseña de hasta 72 bytes.");
        }
    }

    public record RegistrationForm(
            @NotBlank(message = "Escribe tu nombre.")
            @Size(max = 80, message = "El nombre admite hasta 80 caracteres.")
            @Pattern(regexp = "[^\\p{Cntrl}]*", message = "Escribe el nombre en una sola línea.")
            String name,
            @NotBlank(message = "Escribe tu correo.")
            @Email(message = "Escribe un correo válido.")
            @Size(max = 254, message = "El correo admite hasta 254 caracteres.")
            String email,
            @NotBlank(message = "Escribe una contraseña.")
            @Size(min = 10, max = 72, message = "Usa de 10 a 72 caracteres.")
            String password
    ) {}

    public record RecoveryForm(
            @NotBlank(message = "Escribe tu correo.")
            @Email(message = "Escribe un correo válido.")
            @Size(max = 254, message = "El correo admite hasta 254 caracteres.")
            String email
    ) {}

    public record ResetForm(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,256}") String code,
            @NotBlank(message = "Escribe una contraseña.")
            @Size(min = 10, max = 72, message = "Usa de 10 a 72 caracteres.")
            String password
    ) {}
}
