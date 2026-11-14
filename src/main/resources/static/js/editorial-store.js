"use strict";

document.querySelectorAll("[data-feature-hero]").forEach((feature) => {
    const content = feature.querySelector("[data-feature-content]");
    const controls = feature.querySelector("[data-feature-controls]");
    const toggle = controls.querySelector("[data-feature-toggle]");
    const selectors = Array.from(controls.querySelectorAll("[data-feature-select]"));
    const slides = Array.from(content.querySelectorAll("[data-feature-slide]"));
    const fade = feature.hasAttribute("data-feature-fade");
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const rotationDelay = 8000;
    const directions = {ArrowLeft: -1, ArrowUp: -1, ArrowRight: 1, ArrowDown: 1};
    let active = 0;
    let rotationRequested = true;
    let timer;

    function show(index) {
        const next = (index + slides.length) % slides.length;
        if (next === active) return;

        feature.dataset.direction = index < active ? "previous" : "next";
        active = next;
        slides.forEach((slide, position) => {
            const selected = active === position;
            if (fade) {
                slide.classList.toggle("is-active", selected);
            } else {
                slide.hidden = !selected;
            }
            selectors[position].setAttribute("aria-pressed", String(selected));
        });
    }

    function updateRotation() {
        const rotating = rotationRequested && !reducedMotion.matches && !document.hidden;
        if (!rotating) {
            window.clearInterval(timer);
            timer = undefined;
        } else if (timer === undefined) {
            timer = window.setInterval(() => show(active + 1), rotationDelay);
        }
        content.setAttribute("aria-live", rotating ? "off" : "polite");
        toggle.hidden = reducedMotion.matches;
        toggle.classList.toggle("is-paused", !rotationRequested);
        const label = rotationRequested ? "Pausar rotación automática" : "Reproducir carrusel";
        toggle.setAttribute("aria-label", label);
        toggle.title = label;
    }

    function pause() {
        rotationRequested = false;
        updateRotation();
    }

    selectors.forEach((button, index) => {
        button.addEventListener("click", () => {
            pause();
            show(index);
        });
        button.addEventListener("keydown", (event) => {
            if (!Object.hasOwn(directions, event.key)) return;
            event.preventDefault();
            pause();
            show(index + directions[event.key]);
            selectors[active].focus();
        });
    });
    toggle.addEventListener("click", () => {
        if (rotationRequested) {
            pause();
            return;
        }
        rotationRequested = true;
        updateRotation();
        show(active + 1);
    });
    feature.addEventListener("focusin", (event) => {
        if (event.target !== toggle || (!fade && toggle.matches(":focus-visible"))) pause();
    });
    reducedMotion.addEventListener("change", updateRotation);
    document.addEventListener("visibilitychange", updateRotation);
    controls.hidden = false;
    updateRotation();
});

if ("IntersectionObserver" in window) {
    const observer = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
            if (entry.isIntersecting) {
                entry.target.classList.add("editorial-revealed");
                observer.unobserve(entry.target);
            }
        });
    }, {threshold: 0.15});
    document.querySelectorAll("[data-editorial-reveal]").forEach((section) => observer.observe(section));
}

