"use strict";

const log = document.querySelector("#chat-log");
const form = document.querySelector("#chat-form");
const input = document.querySelector("#chat-input");
const send = document.querySelector("#chat-send");
const status = document.querySelector("#chat-status");
const count = document.querySelector("#chat-count");
const notice = document.querySelector("#chat-notice");
const error = document.querySelector("#chat-error");

let socket;
let synchronized = false;
let reconnects = 0;

function appendMessage(message) {
    const nearEnd = log.scrollHeight - log.clientHeight - log.scrollTop < 60;
    const item = document.createElement("li");
    item.className = "chat-message";
    item.dataset.messageId = message.id;

    const meta = document.createElement("span");
    meta.className = "chat-meta";
    meta.textContent = message.author + " · " + message.sentAt;

    const content = document.createElement("p");
    content.className = "chat-body";
    content.textContent = message.content;

    item.append(meta, content);
    for (const current of log.children) {
        const currentId = Number(current.dataset.messageId);
        if (currentId === message.id) return;
        if (currentId > message.id) {
            log.insertBefore(item, current);
            return;
        }
    }
    log.append(item);
    while (log.children.length > 50) log.firstElementChild.remove();
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
        input.focus();
        log.scrollTop = log.scrollHeight;
    }
});

connect();
