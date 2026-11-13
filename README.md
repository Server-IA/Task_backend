# TaskFlow — Backend

API REST de **TaskFlow**, una aplicación de gestión de proyectos ágiles hecha a la medida de equipos de pasantías
(al estilo de Plane o Jira, sin copiar código de ninguno). Proyecto de prácticas universitarias.

- La consume la SPA de [`Task_frontend`](https://github.com/Server-IA/Task_frontend).
- En producción corre en **https://dev.inmero.co** (Dokploy de la universidad); ver [DESPLIEGUE.md](DESPLIEGUE.md).

## Contenido

1. [Qué hace](#qué-hace)
2. [Stack](#stack)
3. [Cómo levantarlo en local](#cómo-levantarlo-en-local)
4. [Variables de entorno](#variables-de-entorno)
5. [Arquitectura](#arquitectura)
6. [Roles y permisos](#roles-y-permisos)
7. [Seguridad](#seguridad)
8. [Tiempo real](#tiempo-real)
9. [Integración con GitHub](#integración-con-github)
10. [Importaciones](#importaciones)
11. [Archivos (Cloudflare R2)](#archivos-cloudflare-r2)
12. [Correos](#correos)
13. [Idiomas](#idiomas)
14. [Tareas programadas](#tareas-programadas)
15. [La API](#la-api)
16. [Modelo de datos](#modelo-de-datos)
17. [Pruebas](#pruebas)
18. [Despliegue](#despliegue)
19. [Convenciones](#convenciones)

## Qué hace

| Área | Funcionalidades |
|---|---|
| **Cuentas** | Registro con verificación de correo, login con JWT y refresh token rotativo, recuperar y cambiar la contraseña, foto de perfil, idioma (español / inglés) y aviso por correo de cada inicio de sesión |
| **Workspaces** | Invitaciones por correo, miembros, **roles personalizados** con 47 permisos, cuota de almacenamiento e integraciones |
| **Proyectos** | Identificador (`WEB`) y color, estados agrupados (Backlog, Sin empezar, En curso, Completado, Cancelado) que son las columnas del Kanban, etiquetas, plantillas de tarea y miembros |
| **Tareas** | Tipos Historia, Tarea y Bug con código legible (`WEB-12`), orden del Kanban, subtareas, dependencias (bloquea / bloqueada por / relacionada), criterios de aceptación, checklist, adjuntos, comentarios con @menciones e historial de cambios |
| **Historias de usuario** | «Partir en tareas», avance automático según sus tareas, aviso de «lista para revisar», cierre y reapertura |
| **Planeación** | Ciclos (sprints) con cierre guiado y retrospectiva (notas, votos y acciones), módulos, hoja de ruta, vistas guardadas y daily standup con agenda configurable |
| **Seguimiento** | Estadísticas (tiempo de ciclo, throughput, burndown, carga por persona), reportes en **PDF**, «Mi trabajo», actividad del workspace, búsqueda y bandeja de notificaciones |
| **GitHub** | GitHub App: ramas desde una tarea, pull requests y checks enlazados a la tarea que nombran, la tarea avanza sola con su PR, y GitHub Actions a mano o por reglas |
| **Importar** | Desde Jira Cloud, Trello, Linear, Asana o un CSV, en segundo plano, con mapeo de estados y personas, informe y **deshacer** |
| **Tiempo real** | Cada pestaña abierta se entera al instante de lo que cambian los demás (Server-Sent Events) |
| **Archivos** | Adjuntos, imágenes en descripciones y comentarios y fotos de perfil en Cloudflare R2, subidos directo desde el navegador con URLs firmadas |

## Stack

| Área | Tecnología |
|---|---|
| Lenguaje y framework | **Java 21** · **Spring Boot 4.1.1** (Spring Framework 7, Spring Security 7, Hibernate 7) |
| Build | Maven con wrapper (`./mvnw`) |
| Base de datos | **PostgreSQL 16** · Spring Data JPA · Flyway |
| Seguridad | Spring Security + JWT (jjwt 0.13) · BCrypt · Bucket4j 8.21 (límites de peticiones) |
| Correos | SMTP (Gmail en producción, Mailpit en local) o Resend 4.29 · plantillas Thymeleaf · envío asíncrono |
| Archivos | Cloudflare R2 con AWS SDK S3 2.56 (URLs firmadas) |
| Reportes | OpenPDF 2.4 |
| Otros | Bean Validation · MapStruct 1.6 · Lombok · springdoc-openapi 3.1 (Swagger UI) |
| Pruebas | JUnit 5 + Mockito · Spring Boot Test · integración contra PostgreSQL real |

## Cómo levantarlo en local

Requisitos: **Java 21** y **Docker**.

```bash
cp .env.example .env          # completar al menos JWT_SECRET (32 caracteres o más)
docker compose up -d          # PostgreSQL 16 (puerto 5432) y Mailpit (correo de prueba, http://localhost:8025)
./mvnw spring-boot:run        # API en http://localhost:8080  (en Windows: .\mvnw.cmd spring-boot:run)
```

Después levanta el frontend (`npm run dev` en `Task_frontend`) y entra a http://localhost:5173. La primera persona que
se registra crea su workspace y queda como project manager.

- **Swagger UI:** http://localhost:8080/swagger-ui.html (botón *Authorize* → pegar el `accessToken` del login).
- **OpenAPI:** http://localhost:8080/v3/api-docs (el frontend genera sus tipos con esto).
- **Salud:** `GET /api/v1/health` → `{"status":"ok"}` cuando la API llega a la base de datos.
- **Esquema:** lo crea Flyway al arrancar (`src/main/resources/db/migration`).
- **Correos en local:** con `MAIL_PROVIDER=smtp` y los valores de `.env.example`, todo correo (verificación,
  recuperar contraseña, invitaciones, avisos) llega a **Mailpit** en http://localhost:8025, sea cual sea el
  destinatario. Nada sale a internet, así que se puede registrar cualquier correo inventado.
- **Archivos en local:** necesitan las credenciales `R2_*` de un bucket de desarrollo cuyo CORS permita
  `http://localhost:5173`. Sin ellas todo funciona menos subir y ver archivos (responden 503).
- **GitHub en local:** necesita una GitHub App de desarrollo y un túnel público hacia la API para recibir sus
  webhooks. Sin las variables `GITHUB_*` la integración aparece como no configurada y lo demás funciona igual.

## Variables de entorno

Se leen del entorno o de un archivo `.env` en la raíz (está en `.gitignore`; nunca se suben secretos).
`.env.example` trae todas con valores para local.

| Variable | Para qué |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Conexión a PostgreSQL |
| `JWT_SECRET` | Clave con la que se firman los JWT (32 caracteres o más). Generarla con `openssl rand -base64 48`. También deriva la clave con la que se cifran los tokens de GitHub guardados |
| `FRONTEND_URL` | URL pública del frontend (por defecto `http://localhost:5173`). Es la base de los enlaces de los correos y su origen es el **único** que CORS deja llamar a la API |
| `MAIL_PROVIDER` | `smtp` o `resend` (por defecto `resend`) |
| `MAIL_FROM` | Remitente, p. ej. `TaskFlow <cuenta@gmail.com>`. Con Gmail tiene que ser la misma cuenta que envía; con Resend, una dirección de un dominio verificado |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` | Servidor SMTP. En local, Mailpit (`localhost:1025`, sin autenticación); en producción, `smtp.gmail.com:587` con una contraseña de aplicación |
| `RESEND_API_KEY` | Solo con `MAIL_PROVIDER=resend`. Si `FRONTEND_URL` no es localhost, la API no arranca sin ella |
| `MAIL_LOG_LINKS` | `true` escribe en el log los enlaces de los correos que no salen. Son **secretos** (quien lea el log puede tomar la cuenta). Vacío: solo cuando `FRONTEND_URL` es localhost |
| `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET` | Cloudflare R2. Endpoint `https://<R2_ACCOUNT_ID>.r2.cloudflarestorage.com`, región `auto` (`R2_ENDPOINT` lo reemplaza si hace falta) |
| `GITHUB_APP_ID`, `GITHUB_APP_SLUG` | Id y slug (`github.com/apps/<slug>`) de la GitHub App |
| `GITHUB_PRIVATE_KEY_PATH` o `GITHUB_PRIVATE_KEY` | Clave privada de la app: la ruta del `.pem` (local) o su contenido con `\n` (producción) |
| `GITHUB_WEBHOOK_SECRET` | Secreto con el que GitHub firma sus webhooks |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | Para que cada persona enlace su cuenta de GitHub |
| `FORWARD_HEADERS_STRATEGY` | `none` (por defecto) o `native` detrás de un proxy (Dokploy/Traefik). Ver [Seguridad](#seguridad) |
| `SPRINGDOC_ENABLED` | Swagger UI y `/v3/api-docs` (por defecto `true`; la imagen de Docker lo pone en `false`) |
| `REFRESH_REUSE_GRACE_SECONDS` | Margen para reusar un refresh token recién rotado (por defecto 30 s) |
| `STORAGE_CLEANUP_CRON` | Limpieza de archivos huérfanos (por defecto cada hora, `0 15 * * * *`) |
| `MAINTENANCE_CRON`, `NOTIFICATION_RETENTION_DAYS` | Limpieza diaria (por defecto 3:30, hora de Bogotá) y días que se guardan las notificaciones leídas (90) |

Otros valores fijos en `application.yml`:
- duración de los tokens: access 15 min, refresh 7 días, verificación 24 h, restablecer contraseña 60 min, invitación 7 días;
- cuota por workspace: 2 GB;
- zona horaria: `America/Bogota`.

## Arquitectura

```
Petición → RequestLanguageFilter → RateLimitFilter → JwtAuthFilter → Controller → Service → Repository → PostgreSQL
                                                                         └→ RealtimeInterceptor (avisa del cambio)
```

- **Controller:** recibe y valida DTOs (`record` con Bean Validation). No toca entidades ni repositorios. Cada
  endpoint lleva su regla de acceso en `@PreAuthorize` (`@workspaceSecurity.isMember(#slug)`,
  `hasPermission(#slug, 'ISSUE_EDIT')`…).
- **Service:** interfaz más `impl/`. Ahí están las reglas de negocio, los permisos finos (por ejemplo, «eliminar sus
  propias tareas» frente a «eliminar cualquier tarea») y las transacciones.
- **Repository:** Spring Data JPA, con `Specification` para los filtros de tareas y bloqueos pesimistas donde hay
  carreras (el número de la tarea, el orden del Kanban, el avance de las historias).
- **Mapper:** MapStruct convierte entidades en DTOs.
- **Errores:** `GlobalExceptionHandler` da a todos los errores la misma forma (ver [La API](#la-api)).

```
src/main/java/com/taskflow/api/
├── config/        Seguridad, CORS, hilos asíncronos, OpenAPI, propiedades (AppProperties)
├── security/      JWT, filtro de autenticación, CredentialsGuard, WorkspaceSecurity y AccessControl (permisos)
├── ratelimit/     RateLimitFilter, RateLimitService, reglas y KnownLogins
├── controller/    Un controlador por recurso
├── service/       Interfaces · service/impl/ implementaciones (tareas, historias, ciclos, daily, retro…)
├── repository/    Repositorios JPA y especificaciones
├── entity/        Entidades (UUID, created_at, updated_at) · entity/enums/
├── dto/           request/ y response/ (records)
├── mapper/        MapStruct
├── exception/     GlobalExceptionHandler y excepciones propias
├── realtime/      Server-Sent Events: RealtimeHub, RealtimeController, RealtimeInterceptor
├── github/        GitHub App: cliente, webhooks, cuentas enlazadas, reglas y mantenimiento
├── imports/       Importación: orígenes (Jira, Trello, Linear, Asana, CSV), plan, escritura y deshacer
├── report/        Reporte de tareas en PDF
├── email/         Envío por SMTP o Resend y EmailNotifier (qué correo se manda y cuándo)
├── storage/       R2, tipos de archivo permitidos y limpieza de huérfanos
├── i18n/          Idioma de la petición y traducción al inglés de los mensajes
├── maintenance/   Limpieza diaria
└── util/          Orden del Kanban, cursores de paginación, menciones, slugs, tokens…
```

## Roles y permisos

Cada workspace tiene roles con permisos sueltos (47, en 11 grupos: tareas, historias, comentarios, ciclos, retro,
módulos, daily, seguimiento, proyectos, workspace e integraciones). El catálogo completo sale de
`GET /api/v1/permissions` y vive en `entity/enums/Permission.java`.

- **Project manager:** tiene todos los permisos y no se puede editar. Lo es quien crea el workspace, y puede nombrar
  a otros.
- **Miembro:** el rol con el que entra cada invitado. Sus permisos se ajustan desde *Ajustes → Roles*.
- **Roles propios** («Líder», «QA»…): se crean con los permisos que se quieran.
- **Acceso a proyectos:** quien no tiene «Ver todos los proyectos» solo ve los proyectos de los que es miembro. Todo
  lo que se lee o se cambia de un proyecto lo comprueba `WorkspaceSecurity.canAccessProject`.
- **Decisiones en la base de datos:** el rol no viaja en el token. Se consulta en cada petición, así un cambio de rol
  cuenta al instante.

## Seguridad

La seguridad fue una prioridad del proyecto: cada punto de abajo tiene pruebas.

**Sesiones**
- El **access token** dura 15 min y el **refresh token** 7 días. Cada refresh **rota** el token. Si alguien reutiliza
  uno ya rotado (fuera del margen de 30 s para dos recargas seguidas), se cierran todas las sesiones de esa cuenta,
  porque significa que lo robaron.
- Los access tokens llevan la versión de las credenciales (claim `cv`). Al cambiar o restablecer la contraseña sube
  la versión, y los tokens anteriores dejan de servir en el acto, sin esperar a que venzan (`CredentialsGuard`).
- `logout` cierra esa sesión entera (el token y todos sus sucesores).
- Los tokens (refresh, verificación, restablecer contraseña, invitación) se guardan **hasheados con SHA-256**. Las
  contraseñas, con **BCrypt**.

**Cuentas**
- **Login sin verificar el correo:** responde 403.
- **Registro:** responde lo mismo exista o no la cuenta (`201 { email }`), y tarda lo mismo porque el hash se calcula
  siempre. Así no sirve para averiguar qué correos están registrados. Si el correo ya tenía cuenta, a su dueño le
  llega un aviso, como mucho uno por hora.
- **Aviso de inicio de sesión:** cada login manda un correo con la IP y el navegador, así el dueño se entera si entra
  otra persona.

**Límites de peticiones** (Bucket4j, en memoria; al pasarse, `429` con `Retry-After`)

| Regla | Límite |
|---|---|
| Login, por IP | 5 por minuto |
| Login de una cuenta desde una IP | 10 cada 15 min |
| Login de una cuenta desde cualquier IP | 50 cada 15 min. No se aplica desde una IP donde la cuenta ya entró bien (`KnownLogins`), así nadie puede dejar al dueño sin entrar con solo saber su correo |
| Registro, por IP | 3 cada 10 min |
| Olvidé mi contraseña · reenviar verificación | 3 cada 15 min |
| Validar un enlace de restablecer contraseña | 10 cada 15 min |
| Cambiar la contraseña | 5 cada 15 min |
| Invitaciones | 20 por hora quien invita · 3 por hora a un mismo correo |
| Subidas de archivos | 300 por hora por persona |
| Webhook de GitHub | 600 por minuto |

**Detrás de un proxy:** con `FORWARD_HEADERS_STRATEGY=native` la IP real sale de `X-Forwarded-For`. Esa cabecera
solo se acepta si la conexión viene de una red interna (el proxy en la red de Docker), así un cliente no puede
falsear su IP. Sin `native`, detrás de un proxy todos compartirían el límite de login.

**Archivos**
- El bucket es privado. Las URLs firmadas duran 15 min para subir y 1 h para descargar.
- La URL de subida lleva `If-None-Match: *`, así no sirve para reemplazar un archivo ya subido.
- Solo se aceptan los tipos de la lista. La extensión manda, y se comprueban los primeros bytes: un ejecutable
  renombrado a `.png` se rechaza. HTML, SVG y ejecutables nunca entran.
- Hay cuota por workspace y límite de tamaño: 5 MB las imágenes y 10 MB los demás archivos.

**Accesos y permisos**
- Cada endpoint comprueba la membresía y el permiso.
- Las acciones diferidas vuelven a comprobar el permiso al ejecutarse: completar la instalación de GitHub, las reglas
  de Actions y las importaciones a un proyecto.

**GitHub e importaciones**
- Los webhooks de GitHub se verifican con su firma HMAC-SHA256 y se descartan los repetidos.
- Los tokens de GitHub de cada persona se guardan cifrados con **AES-GCM**.
- Las credenciales de un origen de importación (token de Jira, Trello…) viven solo en memoria y se olvidan al
  terminar.

**Respuestas de la API**
- Los errores no filtran detalles internos (`server.error.include-message: never`).
- Las respuestas llevan las cabeceras de seguridad de Spring Security.
- En producción Swagger está apagado.

**Riesgos aceptados**
- Los límites viven en memoria (una sola instancia de la API).
- El refresh token se guarda en `localStorage` del navegador: la API lo devuelve en el cuerpo, no en una cookie
  httpOnly. Lo compensa la Content-Security-Policy estricta del frontend.

## Tiempo real

`GET /api/v1/events` abre un canal **Server-Sent Events** por pestaña. Cuando alguien cambia algo, la API avisa a
quien lo está viendo y el frontend vuelve a pedir solo lo que cambió.

- **Sin datos en los avisos:** solo llevan identificadores (workspace, proyecto, tarea, qué tipo de cambio). Lo que se
  muestra se sigue pidiendo a la API con los permisos de siempre, así un aviso nunca filtra nada.
- **Quién recibe cada aviso:**
  - un cambio en un proyecto, quien puede verlo;
  - un cambio del workspace (miembros, GitHub, una importación), sus miembros;
  - una notificación, solo su destinatario.
- **Cuándo se envía:** después del commit, desde un hilo aparte. `RealtimeInterceptor` lo hace solo tras cada
  petición que cambia algo (POST, PUT, PATCH o DELETE con 2xx).
- **Sin eco:** la pestaña que hizo el cambio manda `X-Client-Id` y no recibe su propio eco.
- **Conexión:** latido cada 25 s; el servidor cierra a los 30 min y el navegador reconecta solo.
- **Escala:** las conexiones viven en memoria, lo que basta con una instancia. Con varias habría que pasar a un canal
  compartido (por ejemplo `LISTEN/NOTIFY` de PostgreSQL).

## Integración con GitHub

TaskFlow se conecta como una **GitHub App**. La configuración de producción está en [DESPLIEGUE.md](DESPLIEGUE.md#5-github-app-de-producción).

1. **Conectar el workspace:** *Ajustes → Integraciones → Conectar con GitHub* instala la app en una cuenta u
   organización y elige los repositorios.
2. **Repositorios del proyecto:** en *Ajustes del proyecto → GitHub* se dice qué repositorios son de ese proyecto, el
   formato del nombre de las ramas y a qué estado pasa la tarea cuando se crea su rama o cuando su PR se abre, se
   fusiona o se cierra.
3. **Enlazar cada cuenta:** cada persona enlaza su cuenta de GitHub. Lo que hace desde TaskFlow (crear una rama,
   ejecutar o cancelar un workflow) sale a su nombre y solo donde GitHub le da permiso. Sin cuenta enlazada se puede
   ver, no actuar.
4. **Ramas y PRs:** una rama o un PR que nombra la tarea (`WEB-12` en el nombre de la rama, el título o la
   descripción) queda enlazado a ella. Se ven el estado del PR, sus checks y quién lo hizo, con su foto.
5. **Avance automático:** la tarea cambia de estado sola según su rama y su PR. No se completa sola si le faltan
   criterios de aceptación; en ese caso avisa a los asignados.
6. **GitHub Actions:** ejecutar, repetir o cancelar workflows desde la tarea o desde la página GitHub del proyecto, y
   **reglas** que ejecutan un workflow al completar una tarea (también al cerrar una historia).

Los webhooks entran por `POST /api/v1/github/webhook` y la instalación vuelve por `GET /api/v1/github/setup`.

## Importaciones

*Ajustes del workspace → Importar* trae proyectos desde **Jira Cloud, Trello, Linear y Asana** (conexión directa con
un token) o desde un **CSV** o la exportación JSON de Trello.

1. **Conectar o subir:** se conecta al origen o se sube el archivo. La API lista los proyectos que hay.
2. **Elegir:** qué traer y a dónde, a un proyecto nuevo o a uno existente. Hace falta permiso sobre el destino.
3. **Mapear:** estados, tipos y personas. A quien todavía no está en el workspace se le puede invitar desde ahí: sus
   tareas quedan apuntadas y se le asignan cuando acepta la invitación.
4. **Ejecutar:** corre en segundo plano por tandas. Se puede detener y al final da un **informe** descargable en CSV.
5. **Deshacer:** borra exactamente lo que creó la importación.

Se importan tareas, historias, bugs, subtareas, descripciones (el formato ADF de Jira pasa a Markdown), comentarios,
etiquetas, ciclos, módulos y adjuntos.

## Archivos (Cloudflare R2)

Los archivos nunca pasan por la API: el navegador sube y descarga directo de R2.

1. `POST .../files` pide una URL firmada para subir. La API valida el tipo, el tamaño y la cuota.
2. El navegador hace `PUT` a R2.
3. `POST .../files/{id}/confirm` revisa que el objeto exista, su tamaño y sus primeros bytes, y lo da por subido.
4. Para mostrar archivos se piden las URLs de descarga en lote: `GET /api/v1/files/urls?ids=...`.

**Limpieza:**
- Al borrar un archivo, también en cascada con su tarea, proyecto o workspace, un trigger deja su clave en
  `storage_deletions`. Después del commit y cada minuto se borra de R2, con reintentos si R2 falla.
- Cada hora se borran las subidas sin confirmar de más de 24 h, las imágenes pegadas que ya no aparecen en ningún
  texto desde hace 7 días y las fotos de perfil sin dueño.

El CORS del bucket tiene que permitir `GET`, `PUT` y `HEAD` desde `FRONTEND_URL`, con `AllowedHeaders: ["*"]` y
exponiendo `ETag`.

## Correos

Los correos se arman con plantillas Thymeleaf (`templates/email/`) en el idioma de la persona y se envían en segundo
plano.
- **Cuenta:** verificación, recuperar contraseña, contraseña cambiada, aviso de inicio de sesión e intento de crear
  una cuenta con un correo ya registrado.
- **Workspace:** invitaciones.

Lo demás (asignaciones, menciones, desbloqueos, historias listas para revisar, espacio casi lleno…) llega a la
bandeja de notificaciones de la app.

En producción salen por **SMTP de Gmail**, desde una cuenta exclusiva con contraseña de aplicación. Para usar dominio
propio está **Resend**. En local salen por Mailpit.

## Idiomas

La API responde en español o en inglés según `Accept-Language` (el frontend lo manda con el idioma elegido).
- **Errores y validaciones:** el texto en español es la clave, y `src/main/resources/i18n/en.json` tiene su
  traducción, también con patrones para mensajes con partes variables.
- **Coherencia:** `MessageCoverageTest` falla si un mensaje nuevo del código no tiene traducción.
- **Correos:** usan `i18n/email.properties` y `email_en.properties`.
- **Estados de fábrica:** Backlog, Todo, In Progress, Done y Cancelled se muestran traducidos mientras nadie los
  renombre.

## Tareas programadas

| Qué | Cuándo |
|---|---|
| Borrar de R2 los archivos eliminados (cola `storage_deletions`) | Después de cada commit y cada minuto |
| Archivos huérfanos (subidas sin confirmar, imágenes que ya no se usan) | Cada hora (`STORAGE_CLEANUP_CRON`) |
| Tokens vencidos o usados, invitaciones vencidas, notificaciones leídas de más de 90 días | Diario, 3:30 (`MAINTENANCE_CRON`) |
| Webhooks de GitHub ya atendidos y ejecuciones de Actions antiguas | Diario |

## La API

Prefijo **`/api/v1`**. Todo requiere `Authorization: Bearer <accessToken>` salvo `/auth/**`, `/health`,
`GET /invitations/{token}` y los webhooks de GitHub. El detalle de cada endpoint (cuerpos, respuestas y validaciones)
está en **Swagger** (`/swagger-ui.html` en local).

### Formato de error

```json
{
  "status": 400,
  "message": "Datos inválidos",
  "errors": { "title": "El título es obligatorio" },
  "timestamp": "2026-11-13T15:30:00Z"
}
```

| Código | Cuándo |
|---|---|
| 400 | Validación o regla de negocio. `message` se puede mostrar tal cual; `errors` trae el error de cada campo |
| 401 | Sin token o vencido: hay que llamar a `/auth/refresh` |
| 403 | Sin permiso (o sin acceso al proyecto), o correo sin verificar en el login |
| 404 | No existe |
| 409 | Conflicto: slug o identificador repetido, estado con tareas, descripción editada por otra persona… Algunos traen `code` (`STORY_CLOSED`, `DESCRIPTION_CONFLICT`, `WORKSPACE_LIMIT`) |
| 412 | Al subir a R2: la URL firmada ya se usó |
| 415 | El cuerpo no es JSON |
| 429 | Límite de peticiones: esperar los segundos de `Retry-After` |
| 503 | R2 sin configurar o sin respuesta |

### Autenticación

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| POST | `/auth/register` | `{ email, password, fullName }` | `201 { email }`, igual exista o no la cuenta |
| POST | `/auth/verify-email` | `{ token }` | `{ message }` |
| POST | `/auth/resend-verification` | `{ email }` | `{ message }` (siempre 200) |
| POST | `/auth/login` | `{ email, password }` | `AuthResponse` |
| POST | `/auth/refresh` | `{ refreshToken }` | `AuthResponse` (par nuevo; guardar el refresh nuevo) |
| POST | `/auth/logout` | `{ refreshToken }` | `204` |
| POST | `/auth/forgot-password` | `{ email }` | `{ message }` (siempre 200) |
| GET | `/auth/reset-password/validate?token=` | — | Si el enlace sigue sirviendo |
| POST | `/auth/reset-password` | `{ token, newPassword }` | `{ message }` |

`AuthResponse` = `{ accessToken, refreshToken, tokenType: "Bearer", expiresIn: 900, user }`. Las contraseñas deben
tener entre 8 y 72 caracteres con al menos una letra y un número, y caber en 72 bytes (el límite de BCrypt).

### Mapa de recursos

| Recurso | Ruta base |
|---|---|
| Usuario (perfil, foto, idioma, contraseña) | `/users/me` |
| Cuenta de GitHub de la persona | `/users/me/github` |
| Workspaces y miembros | `/workspaces`, `/workspaces/{slug}/members` |
| Roles y catálogo de permisos | `/workspaces/{slug}/roles`, `/permissions` |
| Invitaciones | `/workspaces/{slug}/invitations`, `/invitations/{token}` |
| Proyectos | `/workspaces/{slug}/projects` |
| Estados · etiquetas · plantillas · vistas guardadas | `/workspaces/{slug}/projects/{projectId}/states` · `labels` · `templates` · `views` |
| Tareas (filtros, orden del Kanban con `PATCH /{id}/move`) | `/workspaces/{slug}/projects/{projectId}/issues` |
| Comentarios · checklist · dependencias de una tarea | `.../issues/{issueId}/comments` · `checklist` · `relations` |
| Historias (listado, partir, cerrar, reabrir) | `.../projects/{projectId}/stories`, `.../issues/{id}/split` · `close` · `reopen` |
| Ciclos y su retrospectiva · módulos | `.../projects/{projectId}/cycles`, `.../cycles/{cycleId}/retro` · `modules` |
| Daily | `.../projects/{projectId}/daily` |
| Estadísticas · reportes PDF | `.../projects/{projectId}/stats` · `reports` |
| Archivos | `.../projects/{projectId}/files`, `/files/urls`, `/workspaces/{slug}/storage/usage` |
| GitHub del workspace · del proyecto | `/workspaces/{slug}/github` · `.../projects/{projectId}/github` |
| Importaciones | `/workspaces/{slug}/imports` |
| Mi trabajo · actividad · búsqueda | `/workspaces/{slug}/my-work` · `activity` · `search` |
| Notificaciones | `/notifications` |
| Tiempo real (SSE) | `/events` |
| Salud | `/health` |

Los listados largos se paginan con `page`/`size` (máximo 200); la bandeja de notificaciones usa cursor (`before`).

## Modelo de datos

PostgreSQL 16 con **48 tablas**. Todo el esquema está en una sola migración,
`src/main/resources/db/migration/V1__esquema_inicial.sql`: las del desarrollo se unificaron antes del primer
despliegue. Hibernate solo valida (`ddl-auto: validate`).

- **Llaves:** las entidades usan **UUID** y `created_at`/`updated_at`. Las tablas intermedias (`issue_assignees`,
  `issue_labels`, `cycle_issues`, `module_issues`…) usan llave compuesta.
- **Código de la tarea (`WEB-12`):** `projects.issue_counter` sube con un `UPDATE` atómico en la misma transacción
  que crea la tarea. Dos peticiones a la vez nunca reciben el mismo número.
- **Orden del Kanban:** `sort_order` es un `double`. Al soltar una tarjeta se usa el promedio de sus vecinas. Si solo
  llega una vecina (tablero filtrado), la otra es la siguiente tarjeta real de la columna. Cuando ya no cabe un
  promedio, la columna se renumera sola.
- **Cálculo en la lectura:** contadores como «3 de 5 tareas», bloqueos o «lista para revisar» se calculan al leer
  (`@Formula`). Así nunca quedan desactualizados.
- **Nombres de columna:** el grupo del estado se llama `state_group` porque `GROUP` es palabra reservada.

## Pruebas

```bash
./mvnw test
```

Son **1082 pruebas** (JUnit 5 + Mockito), organizadas como el código en `src/test/java/com/taskflow/api`:

| Carpeta | Qué cubre |
|---|---|
| `service/` | Reglas de negocio: tareas, historias, ciclos y su cierre, retro, daily, estadísticas, dependencias, notificaciones, archivos… |
| `controller/`, `security/` | La regla de acceso de cada endpoint (`ControllerPermissionsTest`), el filtro JWT, `CredentialsGuard` y `WorkspaceSecurity` |
| `github/`, `imports/` | Webhooks, reglas, cuentas enlazadas; orígenes, mapeo, escritura y deshacer de las importaciones |
| `ratelimit/`, `email/`, `storage/`, `report/`, `i18n/` | Límites, correos, R2 y su limpieza, el PDF y que todo mensaje tenga traducción |
| `integration/` | Flujos completos contra PostgreSQL: carreras con bloqueos, tiempo real, almacenamiento, GitHub, importaciones |
| `config/`, `dto/`, `entity/`, `exception/`, `util/` | Propiedades, validaciones, formato de errores, orden del Kanban, cursores… |

**Pruebas con PostgreSQL:**
- **Base:** las de `integration/` necesitan un PostgreSQL de verdad. Toman `DB_URL`, `DB_USER` y `DB_PASSWORD` del
  entorno o del `.env`, crean una base descartable (`taskflow_it_*`) y la borran al terminar. El usuario necesita
  permiso para crear bases.
- **Sin `DB_URL`:** se omiten sin fallar. En CI hay que definirla, por ejemplo con un servicio `postgres:16`.

## Despliegue

TaskFlow corre en **Dokploy** con tres servicios en una red interna. Solo el frontend tiene dominio y puerto público
(5446).

| Servicio | Imagen | Puerto |
|---|---|---|
| `frontend` | nginx sin privilegios con la SPA; reenvía `/api` a la API | 5446 (dominio) |
| `backend` | Este repositorio: `Dockerfile` multi-stage (Maven → `eclipse-temurin:21-jre-alpine`), usuario sin privilegios, `HEALTHCHECK` contra `/api/v1/health` | 8080 (interno) |
| `db` | PostgreSQL 16 de Dokploy | 5432 (interno) |

**Imagen:** ya trae `FORWARD_HEADERS_STRATEGY=native`, `SPRINGDOC_ENABLED=false` y la memoria de la JVM ajustada al
contenedor.

**Arranque:** Flyway aplica las migraciones pendientes. Cuando la API nueva pasa el health check (unos 30 s después
del build), reemplaza a la anterior sin cortar el servicio.

Los pasos completos (variables, Gmail, GitHub App, CORS de R2) están en **[DESPLIEGUE.md](DESPLIEGUE.md)**.

## Convenciones

- **Ramas:** el trabajo diario va a `develop`; `main` solo recibe pull requests desde `develop`, y lo que llega a
  `main` es lo que se despliega.
- **Migraciones:** nunca editar una ya ejecutada. Siempre una nueva `V{n}__descripcion.sql`.
- **Código:** en inglés, con comentarios y mensajes en español. Los DTOs son `record`.
- **Pruebas:** cada regla nueva lleva su prueba.
- **Secretos:** solo en variables de entorno, nunca en el repositorio.
