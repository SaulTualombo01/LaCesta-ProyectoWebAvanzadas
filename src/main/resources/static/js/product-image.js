const imagen = document.querySelector("#imagen");
const vistaPrevia = document.querySelector("#imagen-vista-previa");
const sinFoto = document.querySelector("#imagen-sin-foto");
const errorImagen = document.querySelector("#imagen-error");
const quitarImagen = document.querySelector("#quitarImagen");
const fotoActual = vistaPrevia.getAttribute("src");

function mostrarFoto(src, alt) {
  if (src) {
    vistaPrevia.src = src;
    vistaPrevia.alt = alt;
  } else {
    vistaPrevia.removeAttribute("src");
  }
  vistaPrevia.hidden = !src;
  sinFoto.hidden = Boolean(src);
}

function actualizarFoto() {
  const archivo = imagen.files[0];
  const error = archivo?.size > 5 * 1024 * 1024 ? "Elige una foto de hasta 5 MB." : "";
  imagen.setCustomValidity(error);
  imagen.setAttribute("aria-invalid", Boolean(error));
  errorImagen.textContent = error;

  mostrarFoto(quitarImagen?.checked ? null : fotoActual, "Foto del producto");
  if (!archivo || error) {
    return;
  }

  if (quitarImagen) {
    quitarImagen.checked = false;
  }
  const lector = new FileReader();
  lector.addEventListener("load", () => {
    if (imagen.files[0] === archivo) {
      mostrarFoto(lector.result, "Vista previa de la foto seleccionada");
    }
  });
  lector.readAsDataURL(archivo);
}

imagen.addEventListener("change", actualizarFoto);
quitarImagen?.addEventListener("change", () => {
  if (quitarImagen.checked) {
    imagen.value = "";
  }
  actualizarFoto();
});
