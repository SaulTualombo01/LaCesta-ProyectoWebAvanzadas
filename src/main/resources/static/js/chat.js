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
const currentUser = form?.dataset.currentUser || "";

let socket;
let synchronized = false;
let reconnects = 0;

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
                if (synchronized) appendMessage(event.message);
                else pending.push(event.message);
                break;
            case "presence":
                count.textContent = event.count;
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

input.addEventListener("input", updateCharCount);

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
        socket.send(content);
        input.value = "";
        updateCharCount();
        input.focus();
        log.scrollTop = log.scrollHeight;
    }
});

updateCharCount();
connect();
