(function () {
  "use strict";

  var DEFAULT_LAT = -0.180653;
  var DEFAULT_LNG = -78.467838;
  var QUITO_SW = [-0.4500, -78.6200];
  var QUITO_NE = [0.0800, -78.2800];

  var panel = document.getElementById("delivery-map-panel");
  var mapEl = document.getElementById("delivery-quito-map");
  if (!panel || !mapEl) return;

  var orderLabel = document.getElementById("delivery-map-order");
  var addressLabel = document.getElementById("delivery-map-address");
  var googleLink = document.getElementById("delivery-map-google-link");
  var closeBtn = document.getElementById("delivery-map-close");
  var statusLabel = document.getElementById("delivery-map-status");
  var coordsLabel = document.getElementById("delivery-map-coords");

  var searchUrl = mapEl.dataset.searchUrl || "/carrito/mapa/buscar";
  var leafletMap = null;
  var leafletMarker = null;

  function ensureMap() {
    if (typeof window.L === "undefined") return false;
    if (leafletMap) {
      window.setTimeout(function () {
        leafletMap.invalidateSize();
      }, 60);
      return true;
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

    leafletMap = L.map(mapEl, {
      center: [DEFAULT_LAT, DEFAULT_LNG],
      zoom: 13,
      minZoom: 11,
      maxZoom: 19,
      maxBounds: L.latLngBounds(QUITO_SW, QUITO_NE),
      maxBoundsViscosity: 1.0
    });

    L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}", {
      attribution: "Tiles &copy; Esri &mdash; Quito, Ecuador",
      maxZoom: 19
    }).addTo(leafletMap);

    leafletMarker = L.marker([DEFAULT_LAT, DEFAULT_LNG], { icon: pinIcon }).addTo(leafletMap);
    window.setTimeout(function () {
      if (leafletMap) leafletMap.invalidateSize();
    }, 100);
    return true;
  }

  function showOrderOnMap(btn) {
    var orderId = btn.dataset.orderId || "";
    var address = (btn.dataset.address || "").trim();
    var phone = (btn.dataset.phone || "").trim();
    var ref = (btn.dataset.ref || "").trim();
    var total = (btn.dataset.total || "").trim();

    panel.hidden = false;
    if (orderLabel) orderLabel.textContent = "Pedido #" + orderId + (total ? " · " + total : "");
    if (addressLabel) {
      addressLabel.textContent = address
        + (phone ? " · Tel: " + phone : "")
        + (ref ? " · Ref: " + ref : "");
    }
    if (googleLink) {
      googleLink.href = "https://www.google.com/maps/search/?api=1&query="
        + encodeURIComponent(address + ", Quito, Ecuador");
    }
    if (statusLabel) statusLabel.textContent = "Buscando dirección en el mapa de Quito…";
    if (coordsLabel) coordsLabel.textContent = "";

    panel.scrollIntoView({ behavior: "smooth", block: "start" });
    if (!ensureMap()) return;

    fetch(searchUrl + "?q=" + encodeURIComponent(address), {
      credentials: "same-origin",
      headers: { Accept: "application/json" }
    })
      .then(function (res) {
        return res.ok ? res.json() : [];
      })
      .then(function (items) {
        if (Array.isArray(items) && items.length > 0) {
          var hit = items[0];
          leafletMarker.setLatLng([hit.lat, hit.lon]);
          leafletMap.flyTo([hit.lat, hit.lon], 16, { duration: 0.8 });
          var popup = document.createElement("div");
          var title = document.createElement("strong");
          title.textContent = "Pedido #" + orderId;
          var br = document.createElement("br");
          var text = document.createElement("span");
          text.textContent = address;
          popup.appendChild(title);
          popup.appendChild(br);
          popup.appendChild(text);
          leafletMarker.bindPopup(popup).openPopup();
          if (statusLabel) statusLabel.textContent = "Pin ubicado en: " + hit.displayName;
          if (coordsLabel) coordsLabel.textContent = hit.lat.toFixed(4) + ", " + hit.lon.toFixed(4);
          if (googleLink) {
            googleLink.href = "https://www.google.com/maps?q=" + hit.lat.toFixed(6) + "," + hit.lon.toFixed(6);
          }
        } else {
          leafletMarker.setLatLng([DEFAULT_LAT, DEFAULT_LNG]);
          leafletMap.setView([DEFAULT_LAT, DEFAULT_LNG], 13);
          if (statusLabel) {
            statusLabel.textContent = "No se encontró el punto exacto en OpenStreetMap; usa «Abrir ruta en Google Maps ↗» para buscar la calle.";
          }
        }
      })
      .catch(function () {
        if (statusLabel) {
          statusLabel.textContent = "Usa «Abrir ruta en Google Maps ↗» para abrir la navegación externa.";
        }
      });
  }

  document.querySelectorAll("[data-delivery-map-btn]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      showOrderOnMap(btn);
    });
  });

  if (closeBtn) {
    closeBtn.addEventListener("click", function () {
      panel.hidden = true;
    });
  }
})();
