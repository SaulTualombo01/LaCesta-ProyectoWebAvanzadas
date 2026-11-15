"use strict";

const log = document.querySelector("#chat-log");
const form = document.querySelector("#chat-form");
const input = document.querySelector("#chat-input");
const send = document.querySelector("#chat-send");
const status = document.querySelector("#chat-status");
const count = document.querySelector("#chat-count");
const notice = document.querySelector("#chat-notice");
const error = document.querySelector("#chat-error");
const searchInput = document.querySelector("#chat-search");
const charCount = document.querySelector("#chat-char-count");
const quickChips = document.querySelectorAll(".chat-chip");
const staffStatus = document.querySelector("#chat-staff-status");
const usersBar = document.querySelector("#chat-users-bar");
const usersList = document.querySelector("#chat-users-list");
const typingIndicator = document.querySelector("#chat-typing");
const currentUser = form?.dataset.currentUser || "";

let socket;
let synchronized = false;
let reconnects = 0;
let lastTypingSentAt = 0;
let typingIdleTimer = null;
let locallyTyping = false;
const activeTypers = new Map();

function initialsFor(name) {
    const clean = (name || "").trim();
    if (!clean) return "?";
    const words = clean.split(/\s+/);
    if (words.length >= 2) {
        return (words[0][0] + words[1][0]).toUpperCase();
    }
    return clean.slice(0, 2).toUpperCase();
}

function normalizeSearch(value) {
    return (value || "")
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .toLowerCase()
        .trim();
}

function applyFilter() {
    if (!searchInput) return;
    const term = normalizeSearch(searchInput.value);
    for (const item of log.children) {
        if (!term) {
            item.hidden = false;
            continue;
        }
        const text = normalizeSearch(item.textContent);
        item.hidden = !text.includes(term);
    }
}

function updateCharCount() {
    if (!charCount) return;
    const length = input.value.length;
    charCount.textContent = `${length} / 400 caracteres · Enter para enviar (Shift+Enter nueva línea)`;
    charCount.classList.toggle("chat-char-limit", length >= 360);
}

function appendMessage(message) {
    const nearEnd = log.scrollHeight - log.clientHeight - log.scrollTop < 60;
    const item = document.createElement("li");
    item.className = "chat-message";
    if (currentUser && message.author === currentUser) {
        item.classList.add("chat-message-own");
    }
    item.dataset.messageId = message.id;

    const meta = document.createElement("span");
    meta.className = "chat-meta";

    const avatar = document.createElement("span");
    avatar.className = "chat-avatar";
    avatar.setAttribute("aria-hidden", "true");
    avatar.textContent = initialsFor(message.author);

    const author = document.createElement("strong");
    author.className = "chat-author";
    author.textContent = message.author;

    meta.append(avatar, author);

    if (message.author === "Administrador" || message.role === "ADMIN") {
        const badge = document.createElement("span");
        badge.className = "chat-badge";
        badge.textContent = "Personal";
        meta.append(badge);
    }

    const time = document.createElement("span");
    time.textContent = "· " + message.sentAt;
    meta.append(time);

    const content = document.createElement("p");
    content.className = "chat-body";
    content.textContent = message.content;

    item.append(meta, content);
    for (const current of log.children) {
        const currentId = Number(current.dataset.messageId);
        if (currentId === message.id) return;
        if (currentId > message.id) {
            log.insertBefore(item, current);
            applyFilter();
            return;
        }
    }
    log.append(item);
    while (log.children.length > 50) log.firstElementChild.remove();
    applyFilter();
    if (nearEnd) log.scrollTop = log.scrollHeight;
}

function renderPresence(event) {
    count.textContent = event.count;
    if (staffStatus && typeof event.staffOnline === "boolean") {
        staffStatus.textContent = event.staffOnline ? "Personal disponible" : "Personal desconectado";
        staffStatus.classList.toggle("staff-online", event.staffOnline);
        staffStatus.classList.toggle("staff-offline", !event.staffOnline);
    }
    if (usersBar && usersList && Array.isArray(event.users)) {
        usersList.replaceChildren();
        event.users.forEach((userName) => {
            const pill = document.createElement("li");
            pill.className = "chat-user-pill";
            pill.textContent = currentUser && userName === currentUser ? `${userName} (tú)` : userName;
            usersList.append(pill);
        });
        usersBar.hidden = event.users.length === 0;
    }
}

function renderTypers() {
    if (!typingIndicator) return;
    if (activeTypers.size === 0) {
        typingIndicator.textContent = "";
        typingIndicator.hidden = true;
        return;
    }
    const labels = Array.from(activeTypers.values(), (entry) => entry.label);
    if (labels.length === 1) {
        typingIndicator.textContent = `${labels[0]} está escribiendo…`;
    } else if (labels.length === 2) {
        typingIndicator.textContent = `${labels[0]} y ${labels[1]} están escribiendo…`;
    } else {
        typingIndicator.textContent = `${labels[0]}, ${labels[1]} y ${labels.length - 2} más están escribiendo…`;
    }
    typingIndicator.hidden = false;
}

function clearTyper(author) {
    const existing = activeTypers.get(author);
    if (existing) {
        clearTimeout(existing.timer);
        activeTypers.delete(author);
        renderTypers();
    }
}

function clearAllTypers() {
    activeTypers.forEach((entry) => clearTimeout(entry.timer));
    activeTypers.clear();
    renderTypers();
}

function handleTypingEvent(event) {
    if (!event.author || (currentUser && event.author === currentUser)) {
        return;
    }
    if (!event.active) {
        clearTyper(event.author);
        return;
    }
    const existing = activeTypers.get(event.author);
    if (existing) {
        clearTimeout(existing.timer);
    }
    const isStaff = event.role === "ADMIN" || event.author === "Administrador";
    const label = isStaff ? `${event.author} (Personal)` : event.author;
    const timer = setTimeout(() => {
        activeTypers.delete(event.author);
        renderTypers();
    }, 3000);
    activeTypers.set(event.author, { label, timer });
    renderTypers();
}

function sendTypingState(active) {
    if (!synchronized || socket?.readyState !== WebSocket.OPEN) {
        return;
    }
    locallyTyping = active;
    socket.send(JSON.stringify({ type: "typing", active }));
}

function notifyTypingActivity() {
    const hasText = input.value.trim().length > 0;
    if (!hasText) {
        if (typingIdleTimer) {
            clearTimeout(typingIdleTimer);
            typingIdleTimer = null;
        }
        if (locallyTyping) {
            sendTypingState(false);
        }
        return;
    }
    const now = Date.now();
    if (!locallyTyping || now - lastTypingSentAt >= 1500) {
        lastTypingSentAt = now;
        sendTypingState(true);
    }
    if (typingIdleTimer) {
        clearTimeout(typingIdleTimer);
    }
    typingIdleTimer = setTimeout(() => {
        typingIdleTimer = null;
        if (locallyTyping) {
            sendTypingState(false);
        }
    }, 2200);
}

function stopTypingActivity() {
    if (typingIdleTimer) {
        clearTimeout(typingIdleTimer);
        typingIdleTimer = null;
    }
    if (locallyTyping) {
        sendTypingState(false);
    }
}

function connect() {
    if (location.protocol !== "https:") {
        status.textContent = "Abre La Cesta por HTTPS para conectarte a la sala.";
        send.disabled = true;
        return;
    }
    const endpoint = form.dataset.ws || "/ws/chat";
    socket = new WebSocket("wss://" + location.host + endpoint);
    const pending = [];

    socket.addEventListener("message", ({data}) => {
        const event = JSON.parse(data);
        switch (event.type) {
            case "history":
                log.replaceChildren();
                event.messages.forEach(appendMessage);
                pending.forEach(appendMessage);
                applyFilter();
                log.scrollTop = log.scrollHeight;
                synchronized = true;
                reconnects = 0;
                status.textContent = "Conectado de forma segura";
                send.disabled = false;
                break;
            case "message":
                clearTyper(event.message.author);
                if (synchronized) appendMessage(event.message);
                else pending.push(event.message);
                break;
            case "presence":
                renderPresence(event);
                break;
            case "typing":
                handleTypingEvent(event);
                break;
            case "notice":
                notice.textContent = event.text;
                break;
            case "error":
                error.textContent = event.text;
                break;
        }
    });

    socket.addEventListener("close", ({code}) => {
        synchronized = false;
        send.disabled = true;
        count.textContent = "0";
        clearAllTypers();
        if (staffStatus) {
            staffStatus.textContent = "Personal desconectado";
            staffStatus.classList.remove("staff-online");
            staffStatus.classList.add("staff-offline");
        }
        if (usersBar && usersList) {
            usersList.replaceChildren();
            usersBar.hidden = true;
        }
        if (code === 1008 || reconnects === 5) {
            status.textContent = "Sin conexión. Recarga la página para volver a entrar.";
            return;
        }
        status.textContent = "Reconectando…";
        setTimeout(connect, 1000 * 2 ** reconnects++);
    });
}

if (searchInput) {
    searchInput.addEventListener("input", applyFilter);
}

input.addEventListener("input", () => {
    updateCharCount();
    notifyTypingActivity();
});

input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey && !send.disabled) {
        event.preventDefault();
        form.requestSubmit();
    }
});

quickChips.forEach((chip) => {
    chip.addEventListener("click", () => {
        const template = chip.dataset.template || "";
        input.value = template;
        updateCharCount();
        notifyTypingActivity();
        input.focus();
        input.setSelectionRange(input.value.length, input.value.length);
    });
});

form.addEventListener("submit", (event) => {
    event.preventDefault();
    const content = input.value.trim();
    error.textContent = "";
    if (!content) {
        error.textContent = "Escribe un mensaje antes de enviarlo.";
        return;
    }
    if (socket?.readyState === WebSocket.OPEN) {
        stopTypingActivity();
        socket.send(content);
        input.value = "";
        updateCharCount();
        input.focus();
        log.scrollTop = log.scrollHeight;
    }
});

updateCharCount();
connect();
