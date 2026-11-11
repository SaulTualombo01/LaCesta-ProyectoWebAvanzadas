"use strict";

document.querySelectorAll("[data-carousel]").forEach((carousel) => {
    const slides = Array.from(carousel.querySelectorAll("[data-carousel-slide]"));
    const captions = Array.from(carousel.querySelectorAll("[data-carousel-caption]"));
    const controls = carousel.querySelector("[data-carousel-controls]");
    const counter = carousel.querySelector("[data-carousel-counter]");
    let active = 0;

    function step(direction) {
        active = (active + direction + slides.length) % slides.length;
        slides.forEach((slide, index) => {
            const offset = (index - active + slides.length) % slides.length;
            slide.dataset.position = ["active", "next", "previous"][offset];
            captions[index].hidden = index !== active;
        });
        carousel.dataset.theme = slides[active].dataset.theme;
        counter.textContent = `${active + 1} / ${slides.length}`;
    }

    controls.querySelectorAll("[data-carousel-step]").forEach((button) => {
        button.addEventListener("click", () => step(Number(button.dataset.carouselStep)));
    });
    controls.addEventListener("keydown", (event) => {
        if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
            event.preventDefault();
            step(event.key === "ArrowRight" ? 1 : -1);
        }
    });
    controls.hidden = false;
});

