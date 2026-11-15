(function () {
  "use strict";

  var CART_STORAGE_KEY = "la_cesta_carrito_v1";
  var DELIVERY_STORAGE_KEY = "la_cesta_datos_envio_v1";
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

  var main = document.getElementById("contenido");
  if (!main) {
    return;
  }

  // Si el pedido fue confirmado, limpiamos los ítems pendientes en localStorage
  if (main.getAttribute("data-cart-confirmed") === "true") {
    try {
      localStorage.removeItem(CART_STORAGE_KEY);
    } catch (e) {
      // Ignorar si el navegador restringe localStorage
    }
    return;
  }

  function syncLocalStorageFromMain(mainEl) {
    if (!mainEl) {
      return;
    }
    var serverItemsJson = mainEl.getAttribute("data-cart-items");
    var isServerEmpty = mainEl.getAttribute("data-cart-empty") === "true";
    if (serverItemsJson === null) {
      return;
    }
    try {
      if (!isServerEmpty) {
        localStorage.setItem(CART_STORAGE_KEY, serverItemsJson);
      } else {
        var savedCart = localStorage.getItem(CART_STORAGE_KEY);
        var syncKey = "la_cesta_sync_attempted";
        if (
          savedCart &&
          savedCart !== "[]" &&
          sessionStorage.getItem(syncKey) !== "true"
        ) {
          sessionStorage.setItem(syncKey, "true");
          var syncForm = document.getElementById("cart-sync-form");
          var syncInput = document.getElementById("cart-sync-input");
          var banner = document.getElementById("cart-restore-banner");
          if (syncForm && syncInput) {
            if (banner) {
              banner.hidden = false;
            }
            syncInput.value = savedCart;
            syncForm.submit();
            return;
          }
        } else {
          localStorage.removeItem(CART_STORAGE_KEY);
          sessionStorage.removeItem(syncKey);
        }
      }
    } catch (e) {
      // Ignorar errores de almacenamiento
    }
  }

  syncLocalStorageFromMain(main);

  // Actualizar en segundo plano (sin recargar ni mover el scroll) al cambiar cantidades en /carrito
  function submitCartUpdateAjax(form, focusTargetInfo) {
    var scrollY = window.scrollY;
    var formData = new FormData(form);

    fetch(form.action, {
      method: "POST",
      body: formData,
      credentials: "same-origin"
    })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("Error al actualizar carrito");
        }
        return response.text();
      })
      .then(function (html) {
        var parser = new DOMParser();
        var doc = parser.parseFromString(html, "text/html");
        var newMain = doc.getElementById("contenido");
        if (!newMain) {
          window.location.reload();
          return;
        }

        // Si el carrito quedó vacío tras la acción, reemplazamos el contenido principal
        if (newMain.getAttribute("data-cart-empty") === "true") {
          try {
            localStorage.removeItem(CART_STORAGE_KEY);
          } catch (e) {
            // Ignorar
          }
          main.setAttribute("data-cart-items", "[]");
          main.setAttribute("data-cart-empty", "true");
          main.innerHTML = newMain.innerHTML;
          updateNavBadge(doc);
          window.scrollTo(0, scrollY);
          return;
        }

        // Actualizar atributos de sincronización y localStorage
        var newItemsJson = newMain.getAttribute("data-cart-items") || "[]";
        main.setAttribute("data-cart-items", newItemsJson);
        main.setAttribute("data-cart-empty", "false");
        try {
          localStorage.setItem(CART_STORAGE_KEY, newItemsJson);
        } catch (e) {
          // Ignorar
        }

        // Actualizar lista de productos
        var currentItemsSection = document.querySelector(".cart-items-section");
        var newItemsSection = doc.querySelector(".cart-items-section");
        if (currentItemsSection && newItemsSection) {
          currentItemsSection.innerHTML = newItemsSection.innerHTML;
        }

        // Actualizar totales del resumen (sin tocar lo que el usuario escribió en dirección/teléfono)
        var currentTotals = document.getElementById("cart-summary-totals");
        var newTotals = doc.getElementById("cart-summary-totals");
        if (currentTotals && newTotals) {
          currentTotals.innerHTML = newTotals.innerHTML;
        }

        // Actualizar avisos solo si hubo advertencia (ej. límite de stock)
        var currentFlash = document.getElementById("cart-flash-container");
        var newFlash = doc.getElementById("cart-flash-container");
        if (currentFlash && newFlash) {
          currentFlash.innerHTML = newFlash.innerHTML;
        }

        // Actualizar contador en el header
        updateNavBadge(doc);

        // Volver a enlazar eventos sobre los nuevos botones
        bindCartItemEvents();

        // Mantener exactamente la misma posición de scroll y el foco en el botón pulsado
        window.scrollTo(0, scrollY);
        if (focusTargetInfo && focusTargetInfo.productId) {
          var targetForm = document.querySelector(
            '.cart-qty-form[data-product-id="' + focusTargetInfo.productId + '"]'
          );
          if (targetForm) {
            var toFocus = null;
            if (focusTargetInfo.step) {
              toFocus = targetForm.querySelector(
                '.cart-step-btn[data-step="' + focusTargetInfo.step + '"]'
              );
            } else if (focusTargetInfo.isInput) {
              toFocus = targetForm.querySelector(".cart-qty-input");
            } else {
              toFocus = targetForm.querySelector(".cart-update-btn");
            }
            if (toFocus && typeof toFocus.focus === "function") {
              toFocus.focus({ preventScroll: true });
            }
          }
        }
      })
      .catch(function () {
        // Si falla fetch por red, guardar scroll y enviar normalmente
        try {
          sessionStorage.setItem(SCROLL_STORAGE_KEY, String(window.scrollY));
        } catch (e) {
          // Ignorar
        }
        form.submit();
      });
  }

  function bindCartItemEvents() {
    // Limpiar localStorage al pulsar "Vaciar carrito"
    var clearBtn = document.querySelector(".cart-clear-btn");
    if (clearBtn && clearBtn.form) {
      clearBtn.form.addEventListener("submit", function () {
        try {
          localStorage.removeItem(CART_STORAGE_KEY);
        } catch (e) {
          // Ignorar
        }
      });
    }

    // Guardar scroll al quitar un producto individual
    var removeBtns = document.querySelectorAll(".cart-remove-btn");
    removeBtns.forEach(function (btn) {
      if (btn.form) {
        btn.form.addEventListener("submit", function () {
          try {
            sessionStorage.setItem(SCROLL_STORAGE_KEY, String(window.scrollY));
          } catch (e) {
            // Ignorar
          }
        });
      }
    });

    // Botones +/- para cambiar cantidad sin recargar la página
    var stepButtons = document.querySelectorAll(".cart-step-btn");
    stepButtons.forEach(function (btn) {
      btn.addEventListener("click", function () {
        var step = parseInt(btn.getAttribute("data-step") || "0", 10);
        var form = btn.closest(".cart-qty-form");
        if (!form) {
          return;
        }
        var input = form.querySelector(".cart-qty-input");
        if (!input) {
          return;
        }
        var current = parseInt(input.value || "1", 10);
        var min = parseInt(input.getAttribute("min") || "1", 10);
        var max = parseInt(input.getAttribute("max") || "999999", 10);
        var next = Math.max(min, Math.min(max, current + step));
        if (next !== current) {
          input.value = String(next);
          submitCartUpdateAjax(form, {
            productId: form.getAttribute("data-product-id"),
            step: String(step)
          });
        }
      });
    });

    // Envío manual del formulario de cantidad (botón Actualizar o Enter en el input)
    var qtyForms = document.querySelectorAll(".cart-qty-form");
    qtyForms.forEach(function (form) {
      form.addEventListener("submit", function (event) {
        event.preventDefault();
        submitCartUpdateAjax(form, {
          productId: form.getAttribute("data-product-id"),
          isInput: true
        });
      });
    });
  }

  bindCartItemEvents();

  // Autocompletado y persistencia de los datos de entrega en localStorage
  var checkoutForm = document.getElementById("cart-checkout-form");
  if (checkoutForm) {
    var fields = ["direccion", "telefono", "referencia"];
    var autofillBadge = document.getElementById("cart-autofill-badge");

    try {
      var savedDeliveryRaw = localStorage.getItem(DELIVERY_STORAGE_KEY);
      if (savedDeliveryRaw) {
        var savedDelivery = JSON.parse(savedDeliveryRaw);
        var restoredAny = false;
        fields.forEach(function (name) {
          var el = document.getElementById(name);
          if (el && (!el.value || el.value.trim() === "") && savedDelivery[name]) {
            el.value = savedDelivery[name];
            if (name === "direccion" || name === "telefono") {
              restoredAny = true;
            }
          }
        });
        if (restoredAny && autofillBadge) {
          autofillBadge.hidden = false;
        }
      }
    } catch (e) {
      // Ignorar errores de parseo
    }

    var saveDeliveryToLocal = function () {
      try {
        var data = {};
        fields.forEach(function (name) {
          var el = document.getElementById(name);
          if (el) {
            data[name] = el.value;
          }
        });
        localStorage.setItem(DELIVERY_STORAGE_KEY, JSON.stringify(data));
      } catch (e) {
        // Ignorar
      }
    };

    fields.forEach(function (name) {
      var el = document.getElementById(name);
      if (el) {
        el.addEventListener("input", saveDeliveryToLocal);
      }
    });

    checkoutForm.addEventListener("submit", saveDeliveryToLocal);
  }
})();
