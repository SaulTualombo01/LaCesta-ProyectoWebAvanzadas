(function () {
  "use strict";

  var SCROLL_STORAGE_KEY = "la_cesta_carrito_scroll_y";

  // Restaurar posición de scroll si hubo recarga tradicional
  try {
    var savedScroll = sessionStorage.getItem(SCROLL_STORAGE_KEY);
    if (savedScroll !== null) {
      sessionStorage.removeItem(SCROLL_STORAGE_KEY);
      var y = parseInt(savedScroll, 10);
      if (!isNaN(y) && y > 0) {
        window.scrollTo(0, y);
      }
    }
  } catch (e) {
    // Ignorar
  }

  function updateNavBadge(doc) {
    var currentNavLink = document.querySelector(".nav-cart-link, .editorial-cart-link");
    var newNavLink = doc.querySelector(".nav-cart-link, .editorial-cart-link");
    if (currentNavLink && newNavLink) {
      currentNavLink.innerHTML = newNavLink.innerHTML;
    }
  }

  // Interceptar formularios "Agregar al carrito" en Inicio, Catálogo, Ofertas y Detalle
  // para que no recarguen la página ni suban el scroll al inicio
  var addForms = document.querySelectorAll(".product-card-actions form, .detail-cart-form");
  addForms.forEach(function (form) {
    form.addEventListener("submit", function (event) {
      event.preventDefault();
      var scrollY = window.scrollY;
      var submitBtn = form.querySelector('button[type="submit"]');
      var originalText = submitBtn ? submitBtn.textContent : "";

      if (submitBtn) {
        submitBtn.disabled = true;
      }

      fetch(form.action, {
        method: "POST",
        body: new FormData(form),
        credentials: "same-origin"
      })
        .then(function (response) {
          if (!response.ok) {
            throw new Error("Error al agregar producto");
          }
          return response.text();
        })
        .then(function (html) {
          var parser = new DOMParser();
          var doc = parser.parseFromString(html, "text/html");
          updateNavBadge(doc);
          window.scrollTo(0, scrollY);

          if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = "✓ Agregado";
            submitBtn.focus({ preventScroll: true });
            window.setTimeout(function () {
              submitBtn.textContent = originalText;
            }, 1000);
          }
        })
        .catch(function () {
          try {
            sessionStorage.setItem(SCROLL_STORAGE_KEY, String(scrollY));
          } catch (e) {
            // Ignorar
          }
          form.submit();
        });
    });
  });

  const cart = document.querySelector(".cart-page");
  const checkout = cart?.querySelector("#cart-checkout-form");
  if (!checkout) return;

  const rows = new Map(Array.from(cart.querySelectorAll(".cart-item"), row => {
    const input = row.querySelector(".cart-qty-input");
    return [row.dataset.productId, {
      row, input, cantidad: input.valueAsNumber, precio: row.dataset.regular, precioFinal: row.dataset.final
    }];
  }));
  const pending = new Map();
  const status = cart.querySelector(".cart-sync-status");
  const retry = cart.querySelector(".cart-sync-retry");
  const checkoutButton = checkout.querySelector('button[type="submit"].cart-checkout-btn');
  const nextStepButton = cart.querySelector("#btn-cart-next-step");
  let saving = false;
  let timer;
  let reloadNeeded = false;

  const money = cents => "$" + (cents / 100).toFixed(2);
  const cents = amount => Math.round(Number(amount) * 100);

  function renderTotals() {
    let units = 0;
    let regular = 0;
    let total = 0;
    for (const [id, line] of rows) {
      const quantity = pending.get(id)?.quantity ?? line.savingQuantity ?? line.cantidad;
      const subtotal = cents(line.precioFinal) * quantity;
      const base = cents(line.precio) * quantity;
      const discount = base - subtotal;
      units += quantity;
      regular += base;
      total += subtotal;
      line.row.querySelector(".cart-item-subtotal .price").textContent = money(subtotal);
      line.row.querySelector(".cart-unit-price").textContent = money(cents(line.precioFinal)) + " c/u";
      const oldPrice = line.row.querySelector(".cart-item-prices del");
      oldPrice.hidden = discount === 0;
      oldPrice.textContent = money(cents(line.precio));
      const pill = line.row.querySelector(".cart-offer-pill");
      pill.hidden = discount === 0;
      pill.textContent = "Ahorras " + money(discount);
    }

    const discount = regular - total;
    cart.querySelector("#cart-items-heading").textContent =
      units === 1 ? "1 artículo seleccionado" : units + " artículos seleccionados";
    cart.querySelectorAll("[data-cart-units]").forEach(el => {
      el.textContent = "Productos (" + units + ")";
    });
    cart.querySelectorAll("[data-cart-regular]").forEach(el => {
      el.textContent = money(regular);
    });
    cart.querySelectorAll("[data-cart-total]").forEach(el => {
      el.textContent = money(total);
    });
    cart.querySelectorAll("[data-cart-discount]").forEach(el => {
      el.parentElement.hidden = discount === 0;
      el.textContent = "-" + money(discount);
    });
    cart.querySelectorAll(".cart-savings-banner").forEach(el => {
      el.hidden = discount === 0;
      el.textContent = "Estás ahorrando " + money(discount);
    });
    const badge = document.querySelector(".cart-nav-badge");
    if (badge) badge.textContent = units;
  }

  function setStatus(message, failed = false) {
    status.textContent = message;
    status.classList.toggle("is-error", failed);
    retry.hidden = !failed;
    retry.textContent = reloadNeeded ? "Recargar carrito" : "Reintentar";
    const blocked = saving || pending.size > 0 || failed;
    if (checkoutButton) checkoutButton.disabled = blocked;
    if (nextStepButton) nextStepButton.disabled = blocked;
  }

  async function flush() {
    if (saving || pending.size === 0) return;
    saving = true;
    setStatus("Guardando cambios…");
    let message = "";
    try {
      while (pending.size > 0) {
        const [id, update] = pending.entries().next().value;
        pending.delete(id);
        const line = rows.get(id);
        line.savingQuantity = update.quantity;
        try {
          const data = new FormData(update.form);
          data.set("cantidad", String(update.quantity));
          data.set("ajax", "true");
          const response = await fetch(update.form.action, {
            method: "POST",
            body: data,
            credentials: "same-origin",
            headers: {Accept: "application/json"}
          });
          if (response.redirected) {
            window.location.assign(response.url);
            return;
          }
          if (!response.ok || !response.headers.get("content-type")?.includes("application/json")) {
            throw new Error("La actualización no pudo guardarse");
          }
          const result = await response.json();
          const confirmed = new Map(result.items.map(item => [item.id, item]));
          if (result.mensaje) message = result.mensaje;
          reloadNeeded = result.items.some(item => !rows.has(item.id));
          for (const [productId, current] of rows) {
            const item = confirmed.get(productId);
            if (!item) {
              pending.delete(productId);
              current.row.remove();
              rows.delete(productId);
              continue;
            }
            current.cantidad = item.cantidad;
            current.precio = item.precio;
            current.precioFinal = item.precioFinal;
            current.input.max = item.stock;
            current.row.querySelector("[data-cart-stock]").textContent = item.stock + " disp.";
            const next = pending.get(productId);
            current.input.value = next?.quantity ?? item.cantidad;
          }
          delete line.savingQuantity;
          renderTotals();
        } catch (error) {
          if (!pending.has(id)) pending.set(id, update);
          throw error;
        } finally {
          delete line.savingQuantity;
        }
      }
      saving = false;
      if (rows.size === 0) {
        window.location.reload();
      } else if (reloadNeeded) {
        setStatus("Tu carrito cambió en otra pestaña. Recarga para revisar los productos.", true);
      } else {
        setStatus(message || "Cambios guardados.");
      }
    } catch {
      saving = false;
      setStatus("No pudimos confirmar los cambios. Reintenta para guardarlos.", true);
    }
  }

  function schedule(form) {
    const line = rows.get(form.dataset.productId);
    if (!line?.input.validity.valid) return;
    pending.set(form.dataset.productId, {form, quantity: line.input.valueAsNumber});
    renderTotals();
    setStatus("Guardando cambios…");
    clearTimeout(timer);
    timer = setTimeout(flush, 150);
  }

  retry.addEventListener("click", () => {
    if (reloadNeeded) window.location.reload();
    else flush();
  });

  cart.addEventListener("click", event => {
    const button = event.target.closest(".cart-step-btn");
    if (!button) return;
    const form = button.form;
    const input = form.querySelector(".cart-qty-input");
    const current = input.valueAsNumber || rows.get(form.dataset.productId).cantidad;
    const next = Math.max(1, Math.min(Number(input.max), current + Number(button.dataset.step)));
    if (next !== input.valueAsNumber) {
      input.value = next;
      schedule(form);
    }
  });

  cart.addEventListener("input", event => {
    if (event.target.matches(".cart-qty-input")) schedule(event.target.form);
  });

  cart.addEventListener("submit", event => {
    if (event.target.matches(".cart-qty-form")) {
      event.preventDefault();
      schedule(event.target);
    } else if (checkoutButton.disabled) {
      event.preventDefault();
    }
  });
})();
