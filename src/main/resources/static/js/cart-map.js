(function () {
  "use strict";

  var DEFAULT_LAT = -0.180653;
  var DEFAULT_LNG = -78.467838;
  var QUITO_SW = [-0.4500, -78.6200];
  var QUITO_NE = [0.0800, -78.2800];

  function isInsideQuito(lat, lng) {
    return lat >= QUITO_SW[0] && lat <= QUITO_NE[0]
      && lng >= QUITO_SW[1] && lng <= QUITO_NE[1];
  }

  var wizard = document.querySelector(".cart-wizard");
  if (!wizard) return;

  var panels = wizard.querySelectorAll("[data-step-panel]");
  var stepItems = wizard.querySelectorAll(".cart-steps-bar [data-step-trigger]");
  var triggers = wizard.querySelectorAll("[data-step-trigger]");

  var mapContainer = document.getElementById("quito-map");
  var searchBox = document.getElementById("map-search-container");
  var searchInput = document.getElementById("map-search-input");
  var searchSpinner = document.getElementById("map-search-spinner");
  var searchClear = document.getElementById("map-search-clear");
  var suggestionsBox = document.getElementById("map-suggestions");
  var warningBanner = document.getElementById("map-coverage-warning");
  var statusText = document.getElementById("map-status-text");
  var coordsText = document.getElementById("map-coords-text");
  var googleLink = document.getElementById("map-google-link");
  var locateBtn = document.getElementById("btn-locate-me");
  var locateLabel = document.getElementById("btn-locate-label");
  var addressInput = document.getElementById("direccion");

  var searchUrl = mapContainer ? mapContainer.dataset.searchUrl : "/carrito/mapa/buscar";
  var reverseUrl = mapContainer ? mapContainer.dataset.reverseUrl : "/carrito/mapa/reversa";

  var coords = { lat: DEFAULT_LAT, lng: DEFAULT_LNG };
  var leafletMap = null;
  var leafletMarker = null;
  var searchTimer = null;
  var currentStep = wizard.dataset.initialStep === "2" ? "2" : "1";
  var initialLookupDone = false;

  function showWarning(msg) {
    if (!warningBanner) return;
    if (msg) {
      warningBanner.textContent = msg;
      warningBanner.hidden = false;
    } else {
      warningBanner.textContent = "";
      warningBanner.hidden = true;
    }
  }

  function updateCoordsUI(lat, lng) {
    coords = { lat: lat, lng: lng };
    if (coordsText) {
      coordsText.textContent = lat.toFixed(4) + ", " + lng.toFixed(4);
    }
    if (googleLink) {
      googleLink.href = "https://www.google.com/maps?q=" + lat.toFixed(6) + "," + lng.toFixed(6);
    }
  }

  function setStatus(text) {
    if (statusText) {
      statusText.textContent = text;
    }
  }

  function hideSuggestions() {
    if (suggestionsBox) {
      suggestionsBox.hidden = true;
    }
  }

  function updateAddressField(value, syncSearch) {
    if (!value) return;
    var trimmed = value.trim();
    if (trimmed.length > 180) {
      trimmed = trimmed.slice(0, 180).trim();
    }
    if (addressInput) {
      addressInput.value = trimmed;
      addressInput.dispatchEvent(new Event("input", { bubbles: true }));
    }
    if (syncSearch && searchInput) {
      searchInput.value = trimmed;
      if (searchClear) searchClear.hidden = trimmed.length === 0;
    }
  }

  function reverseGeocode(lat, lng, overwriteAddress) {
    setStatus("Consultando dirección exacta en Quito…");
    fetch(reverseUrl + "?lat=" + encodeURIComponent(lat) + "&lon=" + encodeURIComponent(lng), {
      credentials: "same-origin",
      headers: { Accept: "application/json" }
    })
      .then(function (res) {
        if (!res.ok) throw new Error("reverse-failed");
        return res.json();
      })
      .then(function (data) {
        if (data && data.displayName) {
          var label = data.displayName;
          setStatus("Ubicación: " + (label.length > 68 ? label.slice(0, 68) + "…" : label));
          if (overwriteAddress || (addressInput && !addressInput.value.trim())) {
            updateAddressField(label, true);
          }
        } else {
          setStatus("Pin fijado en Quito (" + lat.toFixed(4) + ", " + lng.toFixed(4) + ")");
        }
      })
      .catch(function () {
        setStatus("Pin fijado en Quito (" + lat.toFixed(4) + ", " + lng.toFixed(4) + ")");
      });
  }

  function movePin(lat, lng, zoom, fly) {
    updateCoordsUI(lat, lng);
    if (!leafletMap || !leafletMarker) return;
    leafletMarker.setLatLng([lat, lng]);
    if (fly) {
      leafletMap.flyTo([lat, lng], zoom || 16, { duration: 0.9 });
    } else {
      leafletMap.setView([lat, lng], zoom || 16);
    }
  }

  function locateExistingAddress() {
    if (initialLookupDone) return;
    initialLookupDone = true;
    var existing = addressInput ? addressInput.value.trim() : "";
    if (existing.length >= 4) {
      if (searchInput && !searchInput.value.trim()) {
        searchInput.value = existing;
        if (searchClear) searchClear.hidden = false;
      }
      fetch(searchUrl + "?q=" + encodeURIComponent(existing), {
        credentials: "same-origin",
        headers: { Accept: "application/json" }
      })
        .then(function (res) {
          return res.ok ? res.json() : [];
        })
        .then(function (items) {
          if (Array.isArray(items) && items.length > 0) {
            var first = items[0];
            if (isInsideQuito(first.lat, first.lon)) {
              movePin(first.lat, first.lon, 15, false);
              setStatus("Ubicación previa detectada en Quito");
            }
          }
        })
        .catch(function () {
          // Mantener centro de Quito
        });
    }
  }

  function initMap() {
    if (!mapContainer || typeof window.L === "undefined") return;
    if (leafletMap) {
      window.setTimeout(function () {
        leafletMap.invalidateSize();
      }, 60);
      return;
    }

    var L = window.L;
    var pinIcon = L.icon({
      iconUrl: "/vendor/leaflet/images/marker-icon.png",
      iconRetinaUrl: "/vendor/leaflet/images/marker-icon-2x.png",
      shadowUrl: "/vendor/leaflet/images/marker-shadow.png",
      iconSize: [25, 41],
      iconAnchor: [12, 41],
      popupAnchor: [1, -34],
      shadowSize: [41, 41]
    });

    var quitoBounds = L.latLngBounds(QUITO_SW, QUITO_NE);
    leafletMap = L.map(mapContainer, {
      center: [coords.lat, coords.lng],
      zoom: 14,
      minZoom: 11,
      maxZoom: 19,
      maxBounds: quitoBounds,
      maxBoundsViscosity: 1.0,
      zoomControl: true
    });

    L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}", {
      attribution: "Tiles &copy; Esri &mdash; Quito, Ecuador",
      maxZoom: 19
    }).addTo(leafletMap);

    leafletMarker = L.marker([coords.lat, coords.lng], {
      icon: pinIcon,
      draggable: true,
      autoPan: true
    }).addTo(leafletMap);

    leafletMarker.on("dragend", function (e) {
      var pos = e.target.getLatLng();
      if (!isInsideQuito(pos.lat, pos.lng)) {
        showWarning("El punto debe estar dentro del Distrito Metropolitano de Quito.");
        leafletMarker.setLatLng([coords.lat, coords.lng]);
        return;
      }
      showWarning(null);
      updateCoordsUI(pos.lat, pos.lng);
      reverseGeocode(pos.lat, pos.lng, true);
    });

    leafletMap.on("click", function (e) {
      var clickLat = e.latlng.lat;
      var clickLng = e.latlng.lng;
      if (!isInsideQuito(clickLat, clickLng)) {
        showWarning("Por favor selecciona una ubicación dentro de Quito y sus valles.");
        return;
      }
      showWarning(null);
      leafletMarker.setLatLng([clickLat, clickLng]);
      updateCoordsUI(clickLat, clickLng);
      reverseGeocode(clickLat, clickLng, true);
    });

    updateCoordsUI(coords.lat, coords.lng);
    locateExistingAddress();

    window.setTimeout(function () {
      if (leafletMap) leafletMap.invalidateSize();
    }, 100);
  }

  function renderSuggestions(items) {
    if (!suggestionsBox) return;
    suggestionsBox.innerHTML = "";
    if (!Array.isArray(items) || items.length === 0) {
      suggestionsBox.hidden = true;
      return;
    }

    items.forEach(function (item) {
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "map-suggestion-item";
      btn.setAttribute("role", "option");

      var icon = document.createElement("span");
      icon.className = "map-suggestion-pin";
      icon.setAttribute("aria-hidden", "true");
      icon.textContent = "⌖";

      var label = document.createElement("span");
      label.textContent = item.displayName;

      btn.appendChild(icon);
      btn.appendChild(label);

      btn.addEventListener("click", function () {
        if (!isInsideQuito(item.lat, item.lon)) {
          showWarning("La ubicación seleccionada está fuera de Quito.");
          return;
        }
        showWarning(null);
        movePin(item.lat, item.lon, 16, true);
        updateAddressField(item.displayName, true);
        setStatus("Ubicación: " + (item.displayName.length > 68 ? item.displayName.slice(0, 68) + "…" : item.displayName));
        hideSuggestions();
      });

      suggestionsBox.appendChild(btn);
    });

    suggestionsBox.hidden = false;
  }

  if (searchInput) {
    searchInput.addEventListener("input", function () {
      var value = searchInput.value.trim();
      if (searchClear) searchClear.hidden = value.length === 0;
      if (searchTimer) window.clearTimeout(searchTimer);

      if (value.length < 3) {
        hideSuggestions();
        if (searchSpinner) searchSpinner.hidden = true;
        return;
      }

      if (searchSpinner) searchSpinner.hidden = false;
      searchTimer = window.setTimeout(function () {
        fetch(searchUrl + "?q=" + encodeURIComponent(value), {
          credentials: "same-origin",
          headers: { Accept: "application/json" }
        })
          .then(function (res) {
            return res.ok ? res.json() : [];
          })
          .then(function (items) {
            renderSuggestions(items);
          })
          .catch(function () {
            hideSuggestions();
          })
          .finally(function () {
            if (searchSpinner) searchSpinner.hidden = true;
          });
      }, 320);
    });

    searchInput.addEventListener("focus", function () {
      if (suggestionsBox && suggestionsBox.children.length > 0) {
        suggestionsBox.hidden = false;
      }
    });
  }

  if (searchClear && searchInput) {
    searchClear.addEventListener("click", function () {
      searchInput.value = "";
      searchClear.hidden = true;
      hideSuggestions();
      searchInput.focus();
    });
  }

  document.addEventListener("mousedown", function (event) {
    if (searchBox && !searchBox.contains(event.target)) {
      hideSuggestions();
    }
  });

  if (locateBtn) {
    locateBtn.addEventListener("click", function () {
      if (!navigator.geolocation) {
        showWarning("Tu navegador no soporta geolocalización automática.");
        return;
      }
      locateBtn.disabled = true;
      if (locateLabel) locateLabel.textContent = "Buscando…";
      setStatus("Detectando tu ubicación actual en Quito…");

      navigator.geolocation.getCurrentPosition(
        function (pos) {
          var userLat = pos.coords.latitude;
          var userLng = pos.coords.longitude;
          locateBtn.disabled = false;
          if (locateLabel) locateLabel.textContent = "Mi ubicación";

          if (!isInsideQuito(userLat, userLng)) {
            showWarning("Tu ubicación actual está fuera de Quito. Selecciona un punto dentro del mapa de cobertura.");
            setStatus("Cobertura: Distrito Metropolitano de Quito");
            movePin(DEFAULT_LAT, DEFAULT_LNG, 14, true);
            return;
          }

          showWarning(null);
          movePin(userLat, userLng, 16, true);
          reverseGeocode(userLat, userLng, true);
        },
        function () {
          locateBtn.disabled = false;
          if (locateLabel) locateLabel.textContent = "Mi ubicación";
          setStatus("Puedes mover el pin en el mapa de Quito o buscar tu dirección.");
        },
        { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 }
      );
    });
  }

  function showStep(step, scrollTop) {
    currentStep = String(step);
    panels.forEach(function (panel) {
      panel.hidden = panel.dataset.stepPanel !== currentStep;
    });

    stepItems.forEach(function (item) {
      var target = item.dataset.stepTrigger;
      var active = target === currentStep;
      var done = Number(target) < Number(currentStep);
      item.classList.toggle("is-active", active);
      item.classList.toggle("is-done", done);
      if (active) {
        item.setAttribute("aria-current", "step");
      } else {
        item.removeAttribute("aria-current");
      }
    });

    if (currentStep === "2") {
      initMap();
    }

    if (scrollTop) {
      window.scrollTo({ top: 0, behavior: "smooth" });
    }
  }

  triggers.forEach(function (btn) {
    btn.addEventListener("click", function () {
      if (btn.disabled) return;
      showStep(btn.dataset.stepTrigger, true);
    });
  });

  showStep(currentStep, false);
})();
