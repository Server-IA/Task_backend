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

Pruebas unitarias de `AuthService`, `IssueService` (código del issue, orden del Kanban, `completedAt`
y quién puede borrar), `WorkspaceSecurity` y la regla `@PreAuthorize` de cada endpoint (permisos),
`WorkspaceService` (siempre queda un project manager), las estadísticas del proyecto (mediana,
throughput, burndown), «Mi trabajo», el feed de actividad, la búsqueda, `SortOrderCalculator` y el
avance de ciclos.

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
Done y Cancelled.

Dentro de un proyecto, un miembro puede ver y crear tareas, editarlas, moverlas, comentar (y
editar/borrar sus comentarios), meterlas o sacarlas de ciclos y módulos, y ver estados, etiquetas,
miembros, ciclos y módulos. Configurar el proyecto es solo del project manager (los demás reciben
**403**): crear, editar o borrar proyectos; crear, editar, reordenar o borrar estados y etiquetas;
agregar o quitar miembros; crear, editar o borrar ciclos y módulos.

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
  "labelIds": ["uuid"]
}
```

El issue recibe un código legible (`code: "WEB-12"`) que sigue al identificador del proyecto. La
respuesta incluye también su ciclo (`cycle: { id, name }` o `null`), sus módulos (`modules: [...]`) y
`completedAt`: cuándo entró a un estado del grupo `COMPLETED` (`null` si no está completada; se
borra si sale de ese grupo y no cambia al moverla entre dos estados completados).

**Editar** (PATCH parcial): los campos `null` u omitidos no cambian; `assigneeIds`/`labelIds`
reemplazan la lista completa (`[]` la vacía); `description: ""` la borra; para quitar padre o
fechas se envía `clearParent`, `clearStartDate` o `clearDueDate` en `true`.

**Listado con filtros** (se pueden repetir para varios valores y se combinan con AND):

```
GET /issues?state=<id>&state=<id>&priority=HIGH&assignee=<userId>&label=<id>
           &cycle=<id>&module=<id>&search=login&page=0&size=20&sort=sortOrder,asc
```

`search` busca en el título y también por código (`WEB-12` o `12`). Tamaño máximo de página: 200.
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
| GET / POST | `/modules` | Listar / crear (PM) `{ name, description?, startDate?, targetDate? }` |
| GET / PATCH / DELETE | `/modules/{moduleId}` | Ver / editar (PM) / eliminar (PM) |
| POST | `/modules/{moduleId}/issues` | Agregar `{ issueIds: [...] }` (un issue puede estar en varios módulos) |
| DELETE | `/modules/{moduleId}/issues/{issueId}` | Quitar |

Cada ciclo y módulo trae `progress: { totalIssues, completedIssues, percentage }` (completados =
issues en estados del grupo `COMPLETED`). Los ciclos traen además `status`:
`DRAFT | UPCOMING | CURRENT | COMPLETED`. Para listar los issues de un ciclo o módulo se usa el
filtro `?cycle=` o `?module=` del listado de issues.

### Estadísticas, mi trabajo, actividad y búsqueda

Todas responden solo con datos de los proyectos a los que el usuario tiene acceso. «Abierta» = ni
completada ni cancelada. Las semanas empiezan el **lunes**, en la zona horaria `app.timezone`
(America/Bogota).

| Método | Ruta | Permiso | Descripción |
|---|---|---|---|
| GET | `/workspaces/{slug}/projects/{projectId}/stats?weeks=8` | Acceso al proyecto | Estadísticas (`weeks` ∈ 4, 8, 12; otro valor → 400) |
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
- `cycleTimeDays`: mediana de días de `createdAt` a `completedAt` de las completadas en las `weeks`
  semanas mostradas (1 decimal); `previousCycleTimeDays`, la del periodo anterior de igual duración.
  `null` si no hay completadas.
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
  "completedThisWeek": 4, "inProgressCount": 2 }
```

`completedThisWeek` = asignadas a mí y completadas desde el lunes; `inProgressCount` = asignadas a mí
y abiertas en un estado del grupo `STARTED`.

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

**Búsqueda** (`search`): título (sin importar mayúsculas) o código. `WEB-12` busca esa tarea exacta
(y va primera); `12` busca el número en todos los proyectos. Después, las actualizadas más
recientemente.

### Notificaciones

Se generan al **asignar** un issue y al **comentar** (a los asignados y al creador), nunca a quien
hizo la acción. Cada notificación trae `issue: { id, code, title, projectId, workspaceSlug }` para
navegar al issue.

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/notifications?unread=false&page=0&size=20` | Mis notificaciones (más recientes primero) |
| GET | `/notifications/unread-count` | `{ count }` para el badge |
| PATCH | `/notifications/{id}/read` | Marcar como leída |
| POST | `/notifications/read-all` | Marcar todas como leídas |

---

## Modelo de datos

Migraciones en `src/main/resources/db/migration` (`V1__users.sql` … `V8__issue_completed_at.sql`).
`V7` elimina el rol `ADMIN` (los admins e invitaciones con ese rol pasan a `MEMBER`) y `V8` agrega
`issues.completed_at` (rellenado con la fecha del último cambio de estado hacia el estado actual, o
`updated_at`).
Las entidades usan **UUID** y `created_at`/`updated_at`. Las tablas intermedias puras
(`issue_assignees`, `issue_labels`, `cycle_issues`, `module_issues`) usan clave compuesta.

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
