# Requerimientos de La Cesta

## Problema y objetivos

La Cesta representa un minimarket de barrio en Quito. La aplicación aborda tres tareas del negocio. El personal necesita mantener los datos del surtido en un solo lugar, los clientes quieren consultar precios y disponibilidad antes de visitar la tienda y ambos necesitan un canal para conversar sobre productos. La información del negocio y los productos de demostración son ficticios.

**Objetivo general.** Construir una aplicación monolítica que centralice el catálogo de La Cesta y conecte a clientes y personal mediante un chat en tiempo real.

**Objetivos específicos.**

1. Verificar el ciclo de cuenta desde registro hasta restablecimiento de contraseña, con acceso protegido y caducidad de 20 minutos para el enlace de recuperación.
2. Gestionar una entidad Producto de seis atributos de negocio y consultar el catálogo por categoría, con hasta ocho registros por página.
3. Demostrar el intercambio entre dos cuentas simultáneas, el historial persistido y un aviso automático cuando el stock llegue a cero desde un valor positivo.
4. Ejecutar al menos cinco pruebas automatizadas relevantes y documentar una medición de consulta paginada.

## Actores y decisiones

| Actor | Responsabilidad |
| --- | --- |
| Visitante | Registra una cuenta, inicia sesión o solicita recuperación. |
| Cliente | Consulta productos, existencias y precios, y comparte preguntas u opiniones en la sala general. |
| Administrador | Mantiene el catálogo y responde consultas en la sala general. Su UUID de Supabase Auth se configura en el servidor. |

El inventario de esta entrega es el stock actual de cada producto. La promoción es un precio opcional, vigente mientras esté guardado. Las opiniones se conversan en la sala general con el personal. El alcance de esta primera versión cubre consulta de surtido, administración de productos y comunicación sobre el catálogo. Se dejan como ampliaciones del negocio la compra y el pago en línea, las reservas y los movimientos de inventario, las promociones programadas y las reseñas por producto.

## Requisitos funcionales y criterios de aceptación

Todos los requisitos de esta tabla son obligatorios para el primer bimestre. El orden indica cómo probarlos durante la demostración.

| ID | Requisito | Criterio de aceptación |
| --- | --- | --- |
| RF-01 | Registro de cliente | Supabase Auth registra la cuenta y envía el enlace de confirmación. La contraseña se guarda como hash BCrypt. Un formulario inválido muestra un mensaje comprensible. |
| RF-02 | Inicio y cierre de sesión | Supabase Auth verifica las credenciales y Spring abre la sesión del monolito. El cierre termina esa sesión y el acceso privado vuelve a requerir autenticación. |
| RF-03 | Recuperación de contraseña | Supabase Auth envía un enlace con PKCE. La solicitud local caduca en 20 minutos y el código se canjea en el navegador que inició el proceso. Al cambiar la clave se cierran las sesiones anteriores de La Cesta. |
| RF-04 | Inicio privado | Una sesión válida abre el inicio con enlaces a catálogo, sala general y cierre de sesión. El administrador también puede crear productos. |
| RF-05 | Consulta de catálogo | El usuario autenticado abre un listado paginado, filtra por categoría y consulta un producto por su identificador. Cada página muestra hasta ocho productos. |
| RF-06 | Creación y edición de producto | El administrador registra nombre, categoría, descripción, precio, stock y un precio promocional opcional. Solo se aceptan precios positivos, stock entero no negativo y una promoción inferior al precio regular. Puede agregar, reemplazar o retirar una foto JPG o PNG de hasta 5 MB. La foto aparece en el catálogo y en la ficha del producto. |
| RF-07 | Eliminación de producto | El administrador confirma antes de eliminar. Una versión desactualizada requiere revisar el producto y volver a confirmar. |
| RF-08 | Sala general | Dos usuarios autenticados intercambian mensajes sin recargar. El historial conserva los mensajes y la pantalla presenta los últimos 50 y el número de cuentas conectadas. |
| RF-09 | Aviso de agotado | Una edición que cambia el stock de un número positivo a cero genera un aviso visible en la sala para los usuarios conectados. |

## Requisitos no funcionales

| ID | Requisito | Implementación o evidencia |
| --- | --- | --- |
| RNF-01 | Validación en dos niveles | HTML valida requeridos, tipos, formatos, rangos y longitudes. Java aplica Bean Validation y reglas del negocio. El esquema refuerza restricciones de precio y stock. |
| RNF-02 | Seguridad web y datos | Spring Security protege rutas y POST con CSRF. El rol de administrador depende del UUID verificado por Supabase Auth y configurado en el servidor. JPA usa consultas parametrizadas y PostgreSQL exige TLS con verificación del certificado y host. Thymeleaf y el chat insertan los mensajes como texto. |
| RNF-03 | Seguridad WebSocket | El handshake usa la sesión HTTP autenticada y acepta el mismo origen. El servidor limita y normaliza los mensajes. En HTTPS el navegador usa WSS. |
| RNF-04 | Navegación | Inicio, catálogo, formularios y sala usan menús coherentes y se adaptan a pantallas pequeñas. |
| RNF-05 | Rendimiento | Se pagina el catálogo, se indexa el filtro por categoría y se acota la consulta de historial. La sala se actualiza mediante WebSocket. |
| RNF-06 | Calidad | Las migraciones definen el esquema. El README explica cómo ejecutar, probar y conectar la aplicación. |

## Modelo de datos

El esquema `la_cesta` se aplica con Flyway. Los nombres de las tablas coinciden con las migraciones.

| Tabla | Campos y restricciones |
| --- | --- |
| `auth.users` | Identidades, correos y credenciales administrados por Supabase Auth. La tabla histórica `la_cesta.app_user` queda conservada. |
| `productos` | Identificador, nombre, categoría, descripción, precio regular, stock, precio promocional opcional, revisión de la foto y versión de edición. El índice `productos_categoria_id_idx` cubre el filtro de categoría y el orden por identificador descendente. |
| `producto_imagen` | Identificador del producto como clave primaria y foránea, y contenido JPEG. El archivo se lee al solicitar la foto. Se elimina junto con el producto. |
| `chat_message` | Identificador, nombre público del autor, contenido y fecha de envío. El historial consulta por clave primaria en orden descendente. El nombre se guarda al enviar el mensaje y el correo no se difunde en la sala. |

La categoría se guarda como valor enumerado en Producto. La sala general es única. Los avisos de agotado se distribuyen a las conexiones abiertas.

## Cronograma y roles propuestos

| Semana | Actividad y criterio de cierre | Rol propuesto |
| --- | --- | --- |
| 1 | Validar el problema, requisitos, flujo y modelo con el equipo. | Análisis y coordinación |
| 2 | Configurar Maven y Supabase. Probar registro, acceso y recuperación. | Cuentas y seguridad |
| 3 | Completar CRUD, vistas, navegación y validaciones. | Catálogo y experiencia de usuario |
| 4 | Integrar el chat, verificar con dos cuentas, medir y ensayar la defensa. | Tiempo real y calidad |

Para cinco integrantes, se pueden dividir el catálogo y las vistas entre dos personas. Completar con fechas, nombres, aportes y commits reales.

## Guion de defensa

1. **2 min.** Presentar la PYME, el problema y los objetivos medibles.
2. **3 min.** Mostrar los requisitos, el cronograma, el diagrama del monolito y las tablas.
3. **7 min.** Registrar un cliente, confirmar el correo y recuperar una clave mediante Supabase Auth. Como administrador, crear un producto, filtrarlo por categoría y cambiar su stock a cero mientras otro usuario tiene la sala abierta. Mostrar el mensaje y el aviso en ambos navegadores. Probar la confirmación de eliminación.
4. **3 min.** Presentar pruebas, medición, declaración de IA y lecciones aprendidas.
5. Reservar los 5 minutos previstos para preguntas. El equipo debe poder explicar el hash de contraseña, las validaciones, el handshake WebSocket, la ausencia de transporte alternativo en el cliente nativo y el orden entre persistir un cambio y emitir un aviso.

Para la entrega académica, completar los datos del equipo, preparar las diapositivas, publicar el repositorio con los commits de todos los integrantes y etiquetar el commit revisado con `v1.0-b1`.
