# TaskFlow — Backend

API REST de **TaskFlow**, un gestor de tareas para equipos inspirado en [Plane](https://plane.so)
(usado solo como referencia funcional; no se copia código). Proyecto de prácticas universitarias.

Los equipos organizan su trabajo en **workspaces → proyectos → issues**, con estados (Kanban),
prioridades, etiquetas, asignados, sub-issues, ciclos (sprints), módulos, comentarios, historial
de cambios y notificaciones.

## Stack

| Área | Tecnología |
|---|---|
| Lenguaje / framework | Java 21 · Spring Boot 4.1.1 (Spring Framework 7, Spring Security 7, Hibernate 7) |
| Build | Maven (con wrapper `./mvnw`) |
| Base de datos | PostgreSQL 16 en Docker · Spring Data JPA · Flyway |
| Seguridad | Spring Security + JWT (jjwt 0.13) · BCrypt · Bucket4j (rate limiting) |
| Correos | Resend (producción) o SMTP (Mailpit en local) + plantillas Thymeleaf, envío con `@Async` |
| Otros | Bean Validation · MapStruct · Lombok · springdoc-openapi (Swagger UI) |
| Pruebas | JUnit 5 + Mockito |

## Cómo levantarlo en local

Requisitos: **Java 21+** y **Docker**.

```bash
cp .env.example .env          # completar JWT_SECRET (mínimo 32 caracteres)
docker compose up -d          # PostgreSQL 16 (5432) y Mailpit (bandeja de correo en http://localhost:8025)
./mvnw spring-boot:run        # API en http://localhost:8080
```

- Swagger UI: http://localhost:8080/swagger-ui.html (botón **Authorize** → pegar el `accessToken`).
- OpenAPI JSON: http://localhost:8080/v3/api-docs (útil para generar tipos en el front).
- Las tablas las crea Flyway al arrancar (`src/main/resources/db/migration`).
- **Correos en local:** con `MAIL_PROVIDER=smtp` todos los correos (verificación, recuperar contraseña,
  invitaciones, aviso de login) llegan a **Mailpit**, en http://localhost:8025, sin importar a qué
  dirección se envíen. Nada sale a internet, así que puedes registrar cualquier correo de prueba.

### Variables de entorno (`.env`)

| Variable | Descripción |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Conexión a PostgreSQL |
| `JWT_SECRET` | Clave para firmar los JWT (≥ 32 caracteres). Generar una con `openssl rand -base64 48` |
| `MAIL_PROVIDER` | `smtp` (Mailpit en local, o Brevo/Gmail) o `resend` (producción). Por defecto `resend` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` | Servidor SMTP. Los valores de `.env.example` son los de Mailpit (`localhost:1025`, sin autenticación) |
| `RESEND_API_KEY` | API key de Resend (solo con `MAIL_PROVIDER=resend`). **Si está vacía**, los correos no se envían y los enlaces se imprimen en el log de la API |
| `MAIL_FROM` | Remitente. Con Resend debe ser de un **dominio verificado**; sin él, Resend solo permite `onboarding@resend.dev` y únicamente hacia el correo dueño de la cuenta |
| `FRONTEND_URL` | Origen permitido por CORS y base de los enlaces de los correos (por defecto `http://localhost:5173`) |

El archivo `.env` está en `.gitignore`; nunca se suben secretos.

### Pruebas

```bash
./mvnw test
```

Pruebas unitarias de `AuthService`, `IssueService` (código del issue, orden del Kanban, `completedAt`,
quién puede borrar, checklist al crear y aviso de desbloqueo), `WorkspaceSecurity` y la regla
`@PreAuthorize` de cada endpoint (permisos), `WorkspaceService` (siempre queda un project manager),
las estadísticas del proyecto (mediana, throughput, burndown), «Mi trabajo», el feed de actividad, la
búsqueda, `SortOrderCalculator`, el avance de ciclos, las dependencias (duplicados, ciclos y
desbloqueo), la checklist (orden y conteos), las plantillas (por defecto y validaciones), las
menciones, las vistas guardadas (visibilidad y permisos), el cierre de ciclos (a dónde va cada tarea,
409 y ciclo cerrado inmutable), la retrospectiva (votos, permisos, convertir acciones, responsable,
terminar, aviso al equipo), la daily (día hábil anterior, agregación por persona, ciclo en curso, resumen
y bloqueos de otros proyectos), las dependencias entre proyectos, `startedAt` y el tiempo de ciclo, el
filtro por persona de las estadísticas y el orden y los contadores de las vistas guardadas.

## Arquitectura

```
Request → RateLimitFilter → JwtAuthFilter → Controller → Service → Repository → PostgreSQL
```

- **Controller**: recibe y valida DTOs (`record`), nunca toca entidades ni repositorios.
- **Service** (interfaz + `impl/`): lógica de negocio, permisos y `@Transactional`.
- **Repository**: Spring Data JPA (+ `Specification` para los filtros de issues).
- **Mapper**: MapStruct convierte entidades a DTOs.
- Permisos por workspace con `@PreAuthorize("@workspaceSecurity.isManager(#slug)")`; el rol se
  consulta en la BD, no viaja en el token.

```
src/main/java/com/taskflow/api/
├── config/       SecurityConfig, CorsConfig, AsyncConfig, OpenApiConfig, ResendConfig, AppProperties
├── security/     JwtService, JwtAuthFilter, CustomUserDetailsService, WorkspaceSecurity
├── ratelimit/    RateLimitFilter, RateLimitService, RateLimitRule
├── controller/   Un controlador por recurso
├── service/      Interfaces  ·  service/impl/ implementaciones
├── repository/   Repositorios JPA y especificaciones
├── entity/       Entidades + BaseEntity (UUID, created_at, updated_at) · entity/enums/
├── dto/          request/ y response/ (records)
├── mapper/       Mappers de MapStruct
├── exception/    GlobalExceptionHandler y excepciones propias
├── email/        EmailService, ResendEmailService, EmailNotifier
└── util/         Tokens, slugs, IP/navegador, orden del Kanban
```

---

## Guía para el frontend

Prefijo de todas las rutas: **`/api/v1`**. Todas requieren `Authorization: Bearer <accessToken>`
excepto `/auth/**` y `GET /invitations/{token}`.

### Formato de error

Todas las respuestas de error tienen la misma forma (`errors` solo aparece en validaciones):

```json
{
  "status": 400,
  "message": "Datos inválidos",
  "errors": { "title": "El título es obligatorio" },
  "timestamp": "2026-10-22T15:30:00Z"
}
```

| Código | Cuándo |
|---|---|
| 400 | Validación o regla de negocio (el `message` sirve para mostrar en un toast) |
| 401 | Sin token, o `"Token inválido o expirado"` → llamar a `/auth/refresh` |
| 403 | Sin permiso, o email sin verificar en el login |
| 404 | Recurso no encontrado |
| 409 | Conflicto (slug, identificador o email duplicado, estado con issues…) |
| 429 | Rate limit: leer la cabecera `Retry-After` (segundos) y mostrarlo con Sonner |

### Autenticación

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| POST | `/auth/register` | `{ email, password, fullName }` | `201` usuario (sin verificar) |
| POST | `/auth/verify-email` | `{ token }` | `{ message }` |
| POST | `/auth/resend-verification` | `{ email }` | `{ message }` (siempre 200) |
| POST | `/auth/login` | `{ email, password }` | `AuthResponse` |
| POST | `/auth/refresh` | `{ refreshToken }` | `AuthResponse` (nuevo par) |
| POST | `/auth/logout` | `{ refreshToken }` | `204` |
| POST | `/auth/forgot-password` | `{ email }` | `{ message }` (siempre 200) |
| POST | `/auth/reset-password` | `{ token, newPassword }` | `{ message }` |

`AuthResponse`: `{ accessToken, refreshToken, tokenType: "Bearer", expiresIn: 900, user }`.

- Contraseña: 8–72 caracteres con al menos una letra y un número.
- El **access token dura 15 min** y el **refresh token 7 días**. Cada refresh **rota** el token:
  hay que guardar el nuevo. Si se reutiliza un refresh token ya usado, se cierran todas las
  sesiones del usuario (protección contra robo).
- Login sin verificar el email → `403 "Debes verificar tu correo antes de iniciar sesión"`.
- Rate limit por IP: login 5/min, registro 3/10 min, olvidé mi contraseña y reenviar
  verificación 3/15 min.

**Páginas que debe tener el front** (los correos enlazan a ellas):

| Ruta del front | Qué hace |
|---|---|
| `/verify-email?token=…` | Llama a `POST /auth/verify-email` |
| `/reset-password?token=…` | Formulario → `POST /auth/reset-password` |
| `/forgot-password` | Formulario → `POST /auth/forgot-password` (enlace «¿No fuiste tú?» del aviso de login) |
| `/invitations/{token}` | `GET /invitations/{token}` para mostrarla y `POST /invitations/{token}/accept` (requiere sesión con el mismo email invitado) |

### Usuario

| Método | Ruta | Descripción |
|---|---|---|
| GET / PATCH | `/users/me` | Ver / editar perfil (`{ fullName?, avatarUrl? }`) |
| PATCH | `/users/me/password` | `{ currentPassword, newPassword }` → devuelve un `AuthResponse` nuevo (las demás sesiones se cierran) |

### Workspaces e invitaciones

Roles: **project manager** (`OWNER`) y **miembro** (`MEMBER`); no hay administradores. Quien crea el
workspace es su project manager e invita al equipo. Puede haber varios project managers, pero nunca
menos de uno: degradar, quitar o hacer salir al último responde **409**. Cada workspace se identifica
por su **slug** en la URL. Algunos slugs están reservados porque chocan con rutas del frontend
(`login`, `register`, `profile`, `notifications`, `onboarding`, `invitations`, `settings`…); el
backend responde 400 si se usan.

| Método | Ruta | Permiso |
|---|---|---|
| GET / POST | `/workspaces` | Mis workspaces (con `myRole`) / crear (`{ name, slug? }`) |
| GET | `/workspaces/{slug}` | Miembro |
| PATCH | `/workspaces/{slug}` | Project manager |
| DELETE | `/workspaces/{slug}` | Project manager |
| GET | `/workspaces/{slug}/members` | Miembro |
| PATCH | `/workspaces/{slug}/members/{userId}` | Project manager (`{ role: "OWNER" \| "MEMBER" }`) |
| DELETE | `/workspaces/{slug}/members/{userId}` | Project manager, o uno mismo para salir |
| POST / GET | `/workspaces/{slug}/invitations` | Project manager: invitar (`{ email }`, entra como `MEMBER`) / pendientes |
| DELETE | `/workspaces/{slug}/invitations/{invitationId}` | Project manager: cancelar |
| GET | `/invitations/{token}` | Ver invitación (**público**, sin sesión) |
| POST | `/invitations/{token}/accept` | Aceptar (con sesión del email invitado) |

### Proyectos, estados y etiquetas

Base: `/workspaces/{slug}/projects`. El **project manager** ve todos los proyectos; un **miembro**,
solo aquellos de los que es miembro. Al crearlo se generan los estados Backlog, Todo, In Progress,
Done y Cancelled, y las plantillas de tarea Reporte de bug, Historia de usuario y Tarea técnica.

Dentro de un proyecto, un miembro puede ver y crear tareas, editarlas, moverlas, comentar (y
editar/borrar sus comentarios), meterlas o sacarlas de ciclos y módulos, gestionar dependencias y
checklists, guardar vistas personales, y ver estados, etiquetas, miembros, ciclos, módulos y
plantillas. Configurar el proyecto es solo del project manager (los demás reciben **403**): crear,
editar o borrar proyectos; crear, editar, reordenar o borrar estados, etiquetas y plantillas; agregar
o quitar miembros; crear, editar o borrar ciclos y módulos; gestionar las vistas compartidas.

| Método | Ruta | Descripción |
|---|---|---|
| GET / POST | `/projects` | Listar / crear (PM; `{ name, identifier, description? }`; identifier ej. `WEB`) |
| GET / PATCH / DELETE | `/projects/{projectId}` | Ver / editar (PM) / eliminar (PM) |
| GET / POST | `/projects/{projectId}/members` | Miembros / agregar (PM; `{ userId }`, debe estar en el workspace) |
| DELETE | `/projects/{projectId}/members/{userId}` | Quitar miembro (PM) |
| GET / POST | `/projects/{projectId}/states` | Estados (ordenados por `position`) / crear (PM) `{ name, color, group, position? }` |
| PATCH / DELETE | `/projects/{projectId}/states/{stateId}` | Editar o reordenar / borrar (PM; no si tiene issues) |
| GET / POST | `/projects/{projectId}/labels` | Etiquetas / crear (PM) `{ name, color }` |
| PATCH / DELETE | `/projects/{projectId}/labels/{labelId}` | Editar / borrar (PM) |

`group` ∈ `BACKLOG | UNSTARTED | STARTED | COMPLETED | CANCELLED`; los colores van como `#RRGGBB`.

Cada proyecto trae `nextIssueCode`: el código que recibirá la próxima tarea (`"WEB-26"`, para el «→ será
WEB-26» del formulario de nueva tarea). Es orientativo: si otra persona crea una tarea antes, esa se
lleva el número.

### Issues

Base: `/workspaces/{slug}/projects/{projectId}/issues`.

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/issues` | Listado paginado con filtros |
| POST | `/issues` | Crear |
| GET / PATCH / DELETE | `/issues/{issueId}` | Ver / editar / eliminar (PM o quien la creó; si no, 403) |
| PATCH | `/issues/{issueId}/move` | Mover en el Kanban |
| GET | `/issues/{issueId}/sub-issues` | Sub-tareas |
| GET / POST | `/issues/{issueId}/comments` | Comentarios / comentar `{ body }` |
| PATCH / DELETE | `/issues/{issueId}/comments/{commentId}` | Editar (autor) / borrar (autor o PM) |
| GET | `/issues/{issueId}/activity` | Historial de cambios |
| GET / POST | `/issues/{issueId}/relations` | Dependencias / agregar `{ type, issueId }` |
| DELETE | `/issues/{issueId}/relations/{relationId}` | Quitar dependencia |
| GET / POST | `/issues/{issueId}/checklist` | Criterios y checklist / agregar `{ kind, text }` |
| PATCH / DELETE | `/issues/{issueId}/checklist/{itemId}` | Editar, marcar o reordenar `{ text?, done?, position? }` / eliminar |
| GET | `/stories` | Historias del proyecto con su progreso, personas, tareas, bugs y relacionadas |
| POST | `/issues/{issueId}/split` | «Partir en tareas» (PM): `{ items: [{ title, type?, priority?, assigneeIds? }], reopen? }` |
| POST | `/issues/{issueId}/close` | «Cerrar historia» (PM): al primer estado completado |
| POST | `/issues/{issueId}/reopen` | «Reabrir» (PM): al primer estado en curso |

**Crear** — solo `title` es obligatorio:

```json
{
  "title": "Login con Google",
  "description": "…",
  "stateId": "uuid (por defecto, el primer estado)",
  "priority": "URGENT | HIGH | MEDIUM | LOW | NONE",
  "parentId": "uuid",
  "startDate": "2026-10-22",
  "dueDate": "2026-10-30",
  "assigneeIds": ["uuid"],
  "labelIds": ["uuid"],
  "checklist": [ { "kind": "CRITERION | CHECK", "text": "…" } ],
  "templateId": "uuid"
}
```

`checklist` (opcional, máx. 50) crea los criterios y pasos en la misma transacción, en el orden
recibido; es lo que se envía al crear una tarea desde una plantilla. `templateId` (opcional) es la
plantilla con la que se rellenó el formulario: suma un uso a la plantilla (`usageCount`); 400 si no es
de este proyecto.

El issue recibe un código legible (`code: "WEB-12"`) que sigue al identificador del proyecto. La
respuesta incluye también su ciclo (`cycle: { id, name }` o `null`), sus módulos (`modules: [...]`),
`completedAt`: cuándo entró a un estado del grupo `COMPLETED` (`null` si no está completada; se
borra si sale de ese grupo y no cambia al moverla entre dos estados completados), `startedAt`: la
primera vez que entró a un estado del grupo `STARTED` (`null` si nunca pasó por uno; no se borra al
salir), y los contadores `blockedByCount` (tareas que la bloquean y siguen abiertas: si es mayor que 0,
mostrar «bloqueada»), `checklistTotal`/`checklistDone` y `criteriaTotal`/`criteriaDone`. Además:

- `blockedByCodes`: códigos de esas tareas, ordenados (`["AMC-4", "WEB-2"]`; pueden ser de otro
  proyecto) para el chip «bloqueada por WEB-2».
- `blockingCodes`: códigos de las tareas abiertas que esta bloquea mientras siga abierta, para el chip
  «bloquea WEB-7» (o «bloquea 2» si hay varias).
- `checklistUpdatedAt`: último cambio de los criterios o la checklist («actualizado hace…»); `null` si
  nunca tuvo. Cambiar la checklist no modifica `updatedAt`.

**Tipos** (`type`): `STORY` (historia), `TASK` (tarea, por defecto) o `BUG`. Una historia se parte en
tareas (sus hijas por `parentId`); los bugs hijos de una historia son sus «bugs ligados».

- Solo el project manager crea historias, convierte una tarea en historia o al revés (403) y las parte,
  cierra o reabre. Una historia con hijas no cambia de tipo (409) y no puede ser hija de otra tarea (400).
  Pasar a `BUG` con la descripción vacía agrega las secciones del reporte.
- Progreso en la historia: `storyTaskCount` (tareas sin las canceladas), `storyTaskDoneCount`,
  `storyTaskStartedCount`, `openBugCount` y `bugsAfterCloseCount`; los bugs no cuentan en «x de y».
- **Avance automático**: cuando una de sus tareas entra a un estado `STARTED` y la historia sigue en
  Backlog o Todo, pasa sola al primer estado `STARTED` (historial `STORY_AUTO_STATE` sin autor, con
  `oldValue: "Luis empezó WEB-41"` y `newValue` el estado nuevo).
- `readyForReview` («lista para revisar»): abierta, con al menos una tarea, todas completadas o canceladas
  y ningún bug ligado abierto. El estado no cambia; se avisa una vez a los project managers con
  `STORY_READY` (sin autor) y la marca se borra si se reabre una tarea o llega una tarea o un bug abierto.
- Cerrar (pasar a `COMPLETED`/`CANCELLED`) o reabrir una historia es del project manager por cualquier
  camino (`PATCH`, `/move`, `/close`, `/reopen`; si no, 403 «Solo el project manager cierra la historia»).
  Se guarda `closedAt` y `closedBy`.
- Agregar una `TASK` a una historia cerrada (crear con `parentId` o cambiar el padre) responde **409** con
  `code: "STORY_CLOSED"` salvo que llegue `reopenParent: true` (solo PM: la reabre). Un `BUG` se liga sin
  reabrirla. `relatesToId` en el `POST /issues` deja la tarea nueva relacionada (`RELATES`) con esa tarea
  en la misma transacción («crear tarea relacionada»).
- Listado: `?type=TASK&type=BUG` (sin `type`, todos). Las vistas guardadas sin `type` muestran tareas y
  bugs; `groupBy` acepta además `type` (lista) y `story` (carriles del tablero).
- Las tareas de un proyecto que cuentan (`issueCount`, `openIssueCount`, `overdueIssueCount`, el avance de
  ciclos y módulos, las estadísticas y la daily) son el trabajo: tareas y bugs, no las historias. El
  proyecto trae además `openStoryCount` y `unsplitStoryCount` («2 sin partir»).

**Dependencias** (`relations`): `type` = `BLOCKS` (esta tarea bloquea a `issueId`), `BLOCKED_BY`
(`issueId` bloquea a esta; se guarda como el `BLOCKS` inverso) o `RELATES` (simétrica). La otra tarea
puede ser de **cualquier proyecto del workspace** al que el usuario tenga acceso (si no existe o no
tiene acceso, 400). Responde **409** si es la misma tarea, si la relación ya existe o si dos tareas
quedarían bloqueándose mutuamente. Cada cambio queda en el historial de ambas tareas
(`field: "RELATION"`, con textos como `"bloquea WEB-12"`, `"bloqueada por AMC-3"` o
`"relacionada con WEB-7"`; en `newValue` al agregar y en `oldValue` al quitar). Cuando una tarea
abierta pasa a un estado `COMPLETED`, los asignados de cada tarea que bloqueaba y que ya no tiene
otros bloqueos abiertos reciben `ISSUE_UNBLOCKED`.

```json
{ "blocks": [ Rel ], "blockedBy": [ Rel ], "related": [ Rel ] }
Rel = { "relationId": "…", "issue": RelatedIssue, "accessible": true }
RelatedIssue = { "id", "code": "AMC-4", "title", "state": StateResponse, "assignees": [ UserSummary ],
                 "project": { "id", "name", "identifier" }, "dueDate", "blockedByCount", "subIssueCount",
                 "completedSubIssueCount", "checklistTotal", "checklistDone" }
```

Cada lista va ordenada por código, primero las tareas del proyecto de la URL. `accessible: false`
cuando el usuario no tiene acceso al proyecto de la otra tarea: entonces `title` viene `null` y
`assignees` vacío (el código, el proyecto y el estado sí vienen). `POST` devuelve el `Rel` visto desde
la tarea de la URL (`issue` es la otra tarea). Para buscar tareas de otros proyectos al agregar una
dependencia sirve `GET /workspaces/{slug}/search?q=`.

**Criterios y checklist** (`checklist`): `kind` = `CRITERION` (criterio de aceptación) o `CHECK`
(paso); `text` de 1 a 500 caracteres; máximo 100 por tarea (409). El `GET` devuelve
`[ { id, kind, text, done, position, checkedBy, checkedAt } ]`: primero los criterios y luego los
pasos, cada grupo por `position` (0, 1, 2…). Al agregar, el elemento queda al final de los de su tipo;
en el `PATCH`, `position` es el nuevo índice dentro de su tipo y los demás se renumeran. Marcarlo
(`done: true`) guarda `checkedBy` (UserSummary de quien lo marcó) y `checkedAt`; desmarcarlo los
borra. Los marcados antes de esta versión tienen `checkedBy: null` y `checkedAt` aproximado.

**Editar** (PATCH parcial): los campos `null` u omitidos no cambian; `assigneeIds`/`labelIds`
reemplazan la lista completa (`[]` la vacía); `description: ""` la borra; para quitar padre o
fechas se envía `clearParent`, `clearStartDate` o `clearDueDate` en `true`.

**Listado con filtros** (se pueden repetir para varios valores y se combinan con AND):

```
GET /issues?state=<id>&state=<id>&priority=HIGH&assignee=<userId>&label=<id>
           &cycle=<id>&module=<id>&blocked=true&search=login&page=0&size=20&sort=sortOrder,asc
```

`search` busca en el título y también por código (`WEB-12` o `12`). `blocked=true` deja solo las
tareas con al menos un bloqueo abierto (`blocked=false`, las que no tienen): el «N bloqueadas» del
encabezado es el `totalElements` de `?blocked=true&size=1` con los mismos filtros. Tamaño máximo de
página: 200.
Respuesta paginada:

```json
{ "content": [ … ], "page": 0, "size": 20, "totalElements": 57, "totalPages": 3, "first": true, "last": false }
```

**Kanban** — para pintar el tablero: `GET /states` y `GET /issues?size=200` (o una petición por
estado con `?state=`), agrupar por `state.id` y ordenar por `sortOrder`. Al soltar una tarjeta:

```http
PATCH /issues/{issueId}/move
{ "stateId": "<columna destino>", "afterIssueId": "<tarjeta de arriba>", "beforeIssueId": "<tarjeta de abajo>" }
```

`afterIssueId` y `beforeIssueId` son opcionales (sin ninguno, la tarjeta va al final de la
columna). El backend calcula el nuevo `sortOrder` como el promedio de las vecinas, así que solo se
actualiza una fila.

### Ciclos y módulos

Base: `/workspaces/{slug}/projects/{projectId}`.

| Método | Ruta | Descripción |
|---|---|---|
| GET / POST | `/cycles` | Listar / crear (PM) `{ name, description?, startDate?, endDate? }` |
| GET / PATCH / DELETE | `/cycles/{cycleId}` | Ver / editar (PM; `clearDates: true` lo vuelve borrador) / eliminar (PM) |
| POST | `/cycles/{cycleId}/issues` | Agregar `{ issueIds: [...] }` (un issue está en un solo ciclo: se mueve) |
| DELETE | `/cycles/{cycleId}/issues/{issueId}` | Quitar |
| GET | `/cycles/{cycleId}/close-preview` | Vista previa del cierre (PM) |
| POST | `/cycles/{cycleId}/close` | Cerrar el ciclo (PM) |
| GET / POST | `/modules` | Listar / crear (PM) `{ name, description?, startDate?, targetDate? }` |
| GET / PATCH / DELETE | `/modules/{moduleId}` | Ver / editar (PM) / eliminar (PM) |
| POST | `/modules/{moduleId}/issues` | Agregar `{ issueIds: [...] }` (un issue puede estar en varios módulos) |
| DELETE | `/modules/{moduleId}/issues/{issueId}` | Quitar |

Cada ciclo y módulo trae `progress: { totalIssues, completedIssues, percentage }` (completados =
issues en estados del grupo `COMPLETED`). Los ciclos traen además `status`:
`DRAFT | UPCOMING | CURRENT | COMPLETED`, `closedAt` (`null` mientras siga abierto; un ciclo cerrado
siempre está `COMPLETED`) y `closedBy` (UserSummary de quien lo cerró, para «cerrado por …»; `null` si
sigue abierto). Para listar los issues de un ciclo o módulo se usa el filtro `?cycle=` o `?module=` del
listado de issues.

**Tiempo de ciclo** (cierre, retrospectiva y estadísticas): mediana de días «de En curso a Hecho», de
`startedAt` a `completedAt`, redondeada a un decimal. Las tareas completadas que nunca pasaron por un
estado `STARTED` (`startedAt: null`) no cuentan; si no queda ninguna, `null`.

**Cerrar un ciclo** (solo el PM). `close-preview` responde:

```json
{ "cycle": CycleResponse, "completed": [IssueRef], "unfinished": [IssueRef], "completedCount": 8,
  "unfinishedCount": 3, "cycleTimeDays": 2.5, "nextCycle": CycleResponse | null }
```

`IssueRef` es el `RelatedIssue` de las dependencias (`id, code, title, state, assignees, project,
dueDate, blockedByCount, subIssueCount, completedSubIssueCount, checklistTotal, checklistDone`;
`blockedByCount > 0` = «bloqueada»). `completed` = grupo `COMPLETED`; `unfinished` = ni completadas ni
canceladas (las canceladas no salen en ninguna lista y se quedan en el ciclo); ambas por número.
`cycleTimeDays` = tiempo de ciclo de las completadas (ver arriba). `nextCycle` = el siguiente ciclo sin
cerrar por fecha de inicio (para un ciclo sin fechas, el primero que no ha terminado).

```http
POST /cycles/{cycleId}/close
{ "moveTo": "NEXT_CYCLE | BACKLOG", "targetCycleId": "uuid?",
  "overrides": [ { "issueId": "uuid", "moveTo": "BACKLOG" } ], "startRetro": true }
→ { "cycle": CycleResponse, "movedToCycle": 2, "movedToBacklog": 1, "retroStarted": true }
```

Las sin terminar van a `targetCycleId` (o, sin él, al siguiente ciclo) o salen del ciclo (`BACKLOG`,
sin cambiar de estado); `overrides` cambia el destino de tareas puntuales (las que no estén sin
terminar se ignoran). Sin ciclo siguiente y con alguna tarea hacia `NEXT_CYCLE` → 400; un
`targetCycleId` que no es del proyecto o es el mismo ciclo → 400, cerrado → 409. Si el ciclo terminaba
después de hoy, su `endDate` pasa a hoy. Cerrar un ciclo ya cerrado, o que aún no empieza, → 409. Cada
tarea movida registra `CYCLE` en su historial. Un ciclo cerrado no acepta cambios de nombre ni de
fechas (la descripción sí) ni tareas nuevas → 409. `startRetro: true` abre la retrospectiva y avisa a
los miembros del proyecto (notificación `RETRO_STARTED`, menos a quien la abre; solo en la app).

### Retrospectiva

Base: `/workspaces/{slug}/projects/{projectId}/cycles/{cycleId}/retro`.

| Método | Ruta | Permiso | Descripción |
|---|---|---|---|
| GET | (base) | Acceso al proyecto | Ver la retro (sin empezar: `started: false` y sin notas) |
| POST | `/start` | PM | Abrirla (también con `startRetro` al cerrar) y avisar al equipo (`RETRO_STARTED`); si ya estaba abierta, no cambia ni vuelve a avisar |
| POST | `/notes` | Acceso | `{ column: WENT_WELL \| IMPROVE \| ACTION, text, ownerId? }` (1 a 500) → `201` nota |
| PATCH / DELETE | `/notes/{noteId}` | Autor o PM (403) | Editar `{ text?, ownerId?, clearOwner? }` (parcial) / eliminar (`204`) |
| POST / DELETE | `/notes/{noteId}/vote` | Acceso | Votar / quitar mi voto → `{ note, myVotesLeft }` |
| POST | `/notes/{noteId}/convert` | PM | Acción → tarea (`201` `IssueResponse`) |
| POST | `/finish` | PM | Terminarla: notas y votos quedan de solo lectura |

```json
{ "started": true, "finishedAt": null,
  "summary": { "completedCount": 8, "totalCount": 12, "carriedToCycle": 3, "carriedToBacklog": 1,
               "cycleTimeDays": 2.5 },
  "notes": [ { "id": "…", "column": "ACTION", "text": "Automatizar el deploy", "author": UserSummary,
               "owner": UserSummary | null, "createdAt": "…", "votes": 2, "votedByMe": true,
               "issue": IssueRef | null } ],
  "myVotesLeft": 3,
  "previousActions": [ nota ACTION de la retro del ciclo cerrado anterior, con su issue si se convirtió ] }
```

- Columnas: `WENT_WELL` «Qué salió bien», `IMPROVE` «Qué mejorar», `ACTION` «Acciones». Notas en orden
  de creación.
- `summary`: `totalCount` = tareas que siguen en el ciclo + las que movió el cierre; `carriedTo*`
  salen de lo que hizo el cierre.
- Votos: uno por nota y 5 por persona en cada retro (409 si ya votaste o no te quedan). Quitar un voto
  que no existe no cambia nada.
- Crear, editar, borrar y votar solo con la retro abierta y sin terminar (409).
- Responsable (`owner`, «responsable: Luis»): solo en las `ACTION` (si no, 400) y debe ser miembro del
  proyecto (400). `ownerId` lo cambia y `clearOwner: true` lo quita; mismas reglas que editar la nota.
- Convertir: solo `ACTION` (400) y una vez (409). La tarea se crea en el primer estado `UNSTARTED` (o
  el estado por defecto), con el texto como título (cortado a 255 con «…»; entonces el texto completo
  va en la descripción) y la descripción «Acción acordada en la retrospectiva del ciclo «Sprint 3».»,
  asignada al responsable si sigue siendo miembro del proyecto (si no, sin asignar). Se puede convertir
  también con la retro terminada.
- «Cerrado por X» sale de `closedBy` del ciclo (`GET /cycles/{cycleId}`).

### Plantillas de tarea y vistas guardadas

Base: `/workspaces/{slug}/projects/{projectId}`.

| Método | Ruta | Descripción |
|---|---|---|
| GET / POST | `/templates` | Plantillas en su orden / crear (PM) |
| PATCH / DELETE | `/templates/{templateId}` | Editar o reordenar (PM) / eliminar (PM) |
| GET / POST | `/views` | Vistas visibles / guardar una vista |
| PUT | `/views/order` | Ordenar vistas `{ shared?: [ids], personal?: [ids] }` |
| PATCH / DELETE | `/views/{viewId}` | Editar, compartir o reordenar / eliminar |

**Plantillas**: `{ id, name, titlePrefix, description, priority, labels: [LabelResponse], checklist:
[ { kind, text } ], position, usageCount }`. Al crear la tarea, el frontend antepone `titlePrefix` al
título (por ejemplo `"[Bug] "`, con su espacio), usa `description` (markdown), `priority` (`null` = no
la fija) y `labels`, y envía `checklist` en el campo `checklist` y el id de la plantilla en
`templateId` de `POST /issues` (eso suma 1 a `usageCount`, «12 usos»). Crear:
`{ name, titlePrefix?, description?, priority?, labelIds?, checklist? }` (`name` de 1 a 60 y único en
el proyecto → 409; etiquetas del proyecto → si no, 400). Editar es parcial: `titlePrefix` o
`description` vacíos los quitan, `priority: "NONE"` quita la prioridad, `labelIds` y `checklist`
reemplazan la lista y `position` es el nuevo índice (desde 0).

**Vistas guardadas**: `[ { id, name, shared, owner: UserSummary, filters, position, mine, issueCount } ]`,
primero las compartidas del proyecto y luego mis vistas personales, cada grupo por `position`
(`mine` = la creé yo; `issueCount` = tareas que cumplen sus filtros ahora, las mismas que devuelve
`GET /issues` con esos filtros). `filters` usa los mismos nombres que el listado de tareas:

```json
{ "state": ["uuid"], "priority": ["HIGH"], "assignee": ["uuid"], "label": ["uuid"],
  "search": "login", "cycle": "uuid", "module": "uuid", "blocked": true,
  "view": "list | board", "sort": "dueDate,asc", "groupBy": "state | priority | assignee | label" }
```

Crear: `{ name, shared?, filters? }` (`name` de 1 a 40). Editar: `{ name?, filters?, shared?,
position? }` (`filters` reemplaza todos los filtros; `position` es el índice dentro de su grupo; al
cambiar `shared` pasa al final del otro grupo). Una vista personal solo la ve y gestiona su dueño
(para los demás, 404); crear, editar, eliminar o compartir una vista compartida es solo del project
manager (403).

**Ordenar vistas** (`PUT /views/order`): `shared` = ids de **todas** las vistas compartidas en el
nuevo orden (solo el project manager, si no 403); `personal` = ids de **todas** mis vistas personales.
Al menos una de las dos (400); cada lista debe tener exactamente las vistas de su grupo, sin repetir
(400). Devuelve las vistas visibles, como el `GET`.

### Estadísticas, mi trabajo, actividad y búsqueda

Todas responden solo con datos de los proyectos a los que el usuario tiene acceso. «Abierta» = ni
completada ni cancelada. Las semanas empiezan el **lunes**, en la zona horaria `app.timezone`
(America/Bogota).

| Método | Ruta | Permiso | Descripción |
|---|---|---|---|
| GET | `/workspaces/{slug}/projects/{projectId}/stats?weeks=8&assignee=` | Acceso al proyecto | Estadísticas (`weeks` ∈ 4, 8, 12; otro valor → 400; `assignee` = filtro «+ persona») |
| GET | `/workspaces/{slug}/my-work?view=assigned` | Miembro | Mis tareas abiertas: `assigned` \| `created` \| `commented` |
| GET | `/workspaces/{slug}/activity?page=0&size=30&type=all&projectId=&actorId=` | Miembro | Feed paginado (`PageResponse`), lo más reciente primero; `size` máx. 100 |
| GET | `/workspaces/{slug}/activity/summary?days=7` | Miembro | Resumen de los últimos `days` días (1 a 365) |
| GET | `/workspaces/{slug}/search?q=pago&limit=8` | Miembro | `IssueResponse[]`; `limit` máx. 20; `q` vacío → `[]` |

**Estadísticas** (`stats`):

```json
{
  "openCount": 12, "createdThisWeek": 5, "completedThisWeek": 9, "completedLastWeek": 6,
  "overdue": [ { "id": "…", "code": "WEB-2", "title": "…" } ],
  "cycleTimeDays": 3.4, "previousCycleTimeDays": 4.0,
  "throughput": [ { "weekStart": "2026-09-01", "completed": 5 } ],
  "burndown": { "cycleId": "…", "name": "Sprint 1", "startDate": "2026-10-15", "endDate": "2026-10-29",
                "total": 8, "points": [ { "date": "2026-10-15", "remaining": 8 } ] },
  "openByState": [ { "stateId": "…", "name": "Backlog", "color": "#A3A3A3", "group": "BACKLOG", "count": 5 } ],
  "workload": [ { "user": { "id": "…", "email": "…", "fullName": "…", "avatarUrl": null }, "open": 5, "overdue": 1 } ],
  "stale": [ { "id": "…", "code": "WEB-15", "title": "…", "daysWithoutChanges": 21 } ]
}
```

- `overdue`: abiertas con `dueDate` anterior a hoy, la más vencida primero, máximo 10.
- `cycleTimeDays`: tiempo de ciclo (de `startedAt` a `completedAt`, ver «Ciclos») de las completadas
  en las `weeks` semanas mostradas; `previousCycleTimeDays`, la del periodo anterior de igual
  duración. `null` si no hay ninguna que cuente.
- `assignee` (se puede repetir: `?assignee=a&assignee=b`): todas las métricas cuentan solo las tareas
  asignadas ahora a alguna de esas personas (abiertas, creadas, completadas, vencidas, tiempo de
  ciclo, throughput, burndown, por estado, estancadas) y `workload` muestra solo a esas personas.
- `throughput`: exactamente `weeks` elementos, del más antiguo a la semana actual.
- `burndown`: el ciclo en curso (hoy entre sus fechas; si se solapan, el que empezó más tarde) o
  `null`. `total` = tareas del ciclo sin las canceladas; un punto por día desde el inicio hasta hoy
  con las que seguían sin completar al final de ese día.
- `openByState`: solo estados abiertos, en el orden del tablero, con ceros.
- `workload`: tareas abiertas por asignado (una con dos asignados cuenta para ambos); `user: null` =
  sin asignar. De más a menos.
- `stale`: abiertas sin cambios hace más de 14 días, la más antigua primero, máximo 5.

**Mi trabajo** (`my-work`): `issues` trae las tareas abiertas de la vista, ordenadas por `dueDate`
(sin fecha al final), prioridad (URGENT primero) y número:

```json
{ "issues": [ IssueResponse… ], "assignedCount": 7, "createdCount": 4, "commentedCount": 3,
  "completedThisWeek": 4, "inProgressCount": 3, "overdueCount": 1, "projectCount": 2,
  "byState": [ { "name": "En curso", "color": "#F59E0B", "group": "STARTED", "count": 2 },
               { "name": "En revisión", "color": "#8B5CF6", "group": "STARTED", "count": 1 } ],
  "nextDue": IssueRef | null }
```

Los contadores de las asignadas no dependen de la vista pedida. `completedThisWeek` = asignadas a mí y
completadas desde el lunes («4 cerradas»); `inProgressCount` = asignadas a mí y abiertas en un estado
del grupo `STARTED`; `overdueCount` y `projectCount` son el «7 tareas asignadas a ti en 2 proyectos · 1
vencida»; `byState` = mis asignadas abiertas por estado (solo estados con alguna; los de distintos
proyectos con el mismo nombre y grupo se suman), por grupo y orden del tablero: «2 en curso · 1 en
revisión» son las entradas del grupo `STARTED`; `nextDue` = la asignada con la fecha límite más
próxima **después de hoy** («Próxima fecha: mañana · WEB-19»).

**Feed de actividad** (`activity`): `type` = `all` | `state` (cambios de estado, incluido completar) |
`comments` | `assignments`. En los comentarios, `newValue` trae el texto (hasta 240 caracteres).

```json
{ "id": "…", "actor": { … } | null, "field": "STATE", "oldValue": "En curso", "newValue": "En revisión",
  "createdAt": "…", "issue": { "id": "…", "code": "WEB-4", "title": "…" },
  "project": { "id": "…", "name": "Sitio web 2026", "identifier": "WEB" } }
```

Un `projectId` sin acceso devuelve una página vacía.

**Resumen** (`activity/summary`): `changes` = entradas del historial de cualquier tipo (comentarios
incluidos); `comments`; `completed` y `created` = tareas completadas y creadas; `byProject` =
entradas por proyecto, de más a menos (solo proyectos con actividad):

```json
{ "changes": 38, "comments": 12, "completed": 9, "created": 5,
  "byProject": [ { "projectId": "…", "name": "…", "identifier": "WEB", "count": 29 } ] }
```

### Daily

| Método | Ruta | Permiso | Descripción |
|---|---|---|---|
| GET | `/workspaces/{slug}/projects/{projectId}/daily?date=2026-10-28` | Acceso al proyecto | Daily del día (`date` por defecto = hoy en Bogotá) |
| PUT | `/workspaces/{slug}/projects/{projectId}/daily/note?date=…` | Acceso al proyecto | Nota del día `{ text }` (máx. 5000) → `200` nota; vacío la borra → `204` |

```json
{ "date": "2026-10-28", "previousDate": "2026-10-27",
  "cycle": { "id": "…", "name": "Sprint 1", "startDate": "2026-10-15", "endDate": "2026-10-29" } | null,
  "summary": { "moved": 2, "started": 1, "completed": 1, "comments": 4, "blocked": 1 },
  "people": [ { "user": UserSummary,
                "yesterday": [ { "issue": IssueRef, "change": "movió a En revisión",
                                 "fromState": "En curso", "toState": "En revisión" } ],
                "today": [ IssueRef ],
                "blockers": [ { "issue": IssueRef, "blockedBy": [ IssueRef ] } ],
                "openCount": 3, "cycleAssignedCount": 4, "cycleCompletedCount": 1 } ],
  "note": { "text": "…", "updatedBy": UserSummary | null, "updatedAt": "…" } | null }
```

`IssueRef` es el mismo de las dependencias: trae `dueDate` («vence mañana»), `assignees`,
`blockedByCount`, `subIssueCount`/`completedSubIssueCount` y `checklistTotal`/`checklistDone`.

- `previousDate`: el día anterior; si `date` es lunes, sábado o domingo, el viernes.
- `people`: miembros del proyecto y asignados a tareas abiertas, por nombre, aunque no tengan nada.
- `yesterday`: lo que hizo la persona desde el inicio de `previousDate` hasta el inicio de `date` (el
  lunes incluye el fin de semana), una entrada por tarea con el cambio más importante: `completó` >
  `movió a {estado}` > `creó` > `comentó` > `actualizó` (cualquier otro campo). Máximo 8.
- `today`: sus tareas abiertas en un estado `STARTED` o con `dueDate` ≤ `date`, por fecha límite (sin
  fecha al final), prioridad y número. Máximo 8.
- `blockers`: sus tareas abiertas con bloqueos abiertos. `blockedBy` (por código) trae el estado y los
  asignados de cada tarea que bloquea («bloqueada por WEB-2 (Luis)»), aunque esa tarea no aparezca en
  otra parte de la daily; si es de otro proyecto al que no tengo acceso, viene sin título ni asignados.
- `fromState`/`toState`: solo en `completó` y `movió a …` («Por hacer → En curso»), con los nombres
  que guardó el historial.
- `cycle`: el ciclo en curso ese día (sin cerrar y con `date` entre sus fechas), para «Sprint 1 · día 13
  de 15»; `cycleAssignedCount`/`cycleCompletedCount` = tareas de la persona en ese ciclo (sin las
  canceladas) y cuántas completó («4 tareas asignadas · 1 hecha»); 0 si no hay ciclo. `openCount` =
  sus tareas abiertas del proyecto.
- `summary` (mismo periodo que `yesterday`, de todo el proyecto): `moved` = tareas distintas que
  cambiaron de estado, `started` = tareas que empezaron (su `startedAt` cae en el periodo),
  `completed` = completadas, `comments` = comentarios, `blocked` = tareas en `blockers` de alguien.

**Búsqueda** (`search`): título (sin importar mayúsculas) o código. `WEB-12` busca esa tarea exacta
(y va primera); `12` busca el número en todos los proyectos. Después, las actualizadas más
recientemente.

### Notificaciones

Nunca se avisa a quien hizo la acción. Cada notificación trae
`issue: { id, code, title, projectId, workspaceSlug }` para navegar al issue (`null` en
`RETRO_STARTED`), `cycle: { id, name, projectId, projectIdentifier, workspaceSlug }` (solo en
`RETRO_STARTED`) y `comment: { id, excerpt }` en `ISSUE_COMMENTED` e `ISSUE_MENTIONED` (el texto del
comentario en una línea, con las menciones como «@Nombre», hasta 200 caracteres con «…»; `null` si el
comentario se borró). Tipos (`type`):

- `ISSUE_ASSIGNED`: te asignaron la tarea.
- `ISSUE_COMMENTED`: comentaron una tarea que tienes asignada o que creaste.
- `ISSUE_MENTIONED`: te mencionaron en un comentario (`"Ana te mencionó en WEB-12: …"`). Las
  menciones se escriben en el cuerpo como `@[Nombre Visible](id-del-usuario)`; solo cuentan los
  miembros del proyecto y los project managers (el resto se ignora). Quien está mencionado recibe
  solo la mención, no además `ISSUE_COMMENTED`. Al editar un comentario se avisa únicamente a los
  mencionados por primera vez. Para el autocompletado sirven `GET /projects/{projectId}/members`
  más los project managers de `GET /workspaces/{slug}/members` (`role: "OWNER"`).
- `ISSUE_UNBLOCKED`: se completó el último bloqueo abierto de una tarea que tienes asignada
  (`"WEB-15 ya no está bloqueada: WEB-12 se completó"`).
- `RETRO_STARTED`: se abrió la retrospectiva de un ciclo de un proyecto del que eres miembro
  (`"Ana abrió la retrospectiva de «Sprint 1»: agrega tus notas"`). Solo en la app (sin correo).
- `STORY_READY`: una historia quedó lista para revisar (a los project managers, sin `actor`:
  `"WEB-15 está lista para revisar: Pantalla de perfil de usuario"`). Solo en la app.

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/notifications?unread=false&type=&page=0&size=20` | Mis notificaciones (más recientes primero); `type` filtra (se puede repetir; sin `type`, todas) |
| GET | `/notifications/unread-count` | `{ count }` para el badge |
| GET | `/notifications/counts` | `{ unread, unreadMentions, mentions }` para las pestañas de la bandeja |
| PATCH | `/notifications/{id}/read` | Marcar como leída |
| POST | `/notifications/read-all` | Marcar todas como leídas |

---

## Modelo de datos

Migraciones en `src/main/resources/db/migration` (`V1__users.sql` … `V19__notification_retro_and_comment.sql`).
`V7` elimina el rol `ADMIN` (los admins e invitaciones con ese rol pasan a `MEMBER`) y `V8` agrega
`issues.completed_at` (rellenado con la fecha del último cambio de estado hacia el estado actual, o
`updated_at`). `V9` crea `issue_relations` (un índice único sobre el par de tareas sin importar la
dirección y el tipo evita duplicados y bloqueos mutuos), `V10` `issue_checklist_items`, `V11`
`issue_templates` (+ `issue_template_labels` e `issue_template_items`) con las tres plantillas por
defecto para los proyectos existentes, `V12` `saved_views` (filtros en `jsonb`), `V13` agrega los
tipos de notificación `ISSUE_UNBLOCKED` e `ISSUE_MENTIONED`, `V14` el cierre de ciclos
(`cycles.closed_at`, `closed_by_id`, `carried_to_cycle`, `carried_to_backlog`, `retro_started_at`,
`retro_finished_at`) y la retrospectiva (`retro_notes`, cuya columna se llama `retro_column`, y
`retro_note_votes`), `V15` `daily_notes` (una nota por proyecto y día), `V16` `issues.started_at` (la
primera vez que la tarea entró a un estado `STARTED`; rellenado desde el historial: el primer cambio
hacia un estado `STARTED` por nombre, o la creación si se creó en uno; las que hoy están en curso sin
explicación en el historial toman su último cambio de estado; las demás quedan en `NULL`), `V17` quién
y cuándo marcó cada elemento de la checklist (`checked_by_id`, `checked_at`; los ya marcados toman
`checked_at = updated_at`) e `issues.checklist_updated_at`, `V18` `issue_templates.usage_count` y
`retro_notes.owner_id`, y `V19` el tipo `RETRO_STARTED` con `notifications.cycle_id` y
`notifications.comment_id` (rellenado para las notificaciones de comentarios ya enviadas). `V24` agrega
`issues.issue_type` (`STORY`, `TASK`, `BUG`; las que existían quedan como tareas, salvo títulos que empiezan
con «[Bug]», que pasan a bugs, y descripciones con la cita «> **Como**» de la historia de usuario, que pasan a
historias), `issues.closed_at`/`closed_by_id` y `issues.review_notified`, el tipo de cada plantilla
(`issue_templates.issue_type`: «Reporte de bug» → bug, «Historia de usuario» → historia) y la notificación
`STORY_READY`.
Las entidades usan **UUID** y `created_at`/`updated_at`. Las tablas intermedias puras
(`issue_assignees`, `issue_labels`, `cycle_issues`, `module_issues`, `issue_template_labels`,
`retro_note_votes`) usan clave compuesta.

Detalles a tener en cuenta:

- **Código del issue (`WEB-12`)**: `projects.issue_counter` se incrementa con un `UPDATE` atómico
  en la misma transacción que crea el issue, así dos peticiones simultáneas nunca obtienen el
  mismo número.
- **Orden del Kanban**: `sort_order` es un `double`; las tarjetas se separan por 65535 y al mover
  una se usa el promedio de sus vecinas. Si después de muchos movimientos ya no cabe un promedio,
  se renumera la columna automáticamente.
- La columna del grupo del estado se llama `state_group` (porque `GROUP` es palabra reservada en
  SQL) y la de leído en notificaciones `is_read`.
- Los tokens (refresh, verificación, reset, invitación) se guardan **hasheados con SHA-256**.

## Convenciones

- Ramas: `main`, `develop` y `feature/<nombre>`.
- Commits: [Conventional Commits](https://www.conventionalcommits.org/es/) (`feat(auth): …`).
- Migraciones: nunca editar una ya ejecutada; siempre crear una nueva `V{n}__descripcion.sql`.
- DTOs con `record`. Secretos solo en variables de entorno.

## Docker / despliegue

El `Dockerfile` (multi-stage, Java 21) queda listo para desplegar en Dokploy más adelante. El
rate limiting guarda los contadores en memoria (una sola instancia). Detrás de un proxy habrá que
activar `server.forward-headers-strategy` para que la IP del cliente sea la real.
