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
| Correos | Resend (SDK Java) + plantillas Thymeleaf, envío con `@Async` |
| Otros | Bean Validation · MapStruct · Lombok · springdoc-openapi (Swagger UI) |
| Pruebas | JUnit 5 + Mockito |

## Cómo levantarlo en local

Requisitos: **Java 21+** y **Docker**.

```bash
cp .env.example .env          # completar JWT_SECRET (mínimo 32 caracteres)
docker compose up -d          # PostgreSQL 16 en localhost:5432
./mvnw spring-boot:run        # API en http://localhost:8080
```

- Swagger UI: http://localhost:8080/swagger-ui.html (botón **Authorize** → pegar el `accessToken`).
- OpenAPI JSON: http://localhost:8080/v3/api-docs (útil para generar tipos en el front).
- Las tablas las crea Flyway al arrancar (`src/main/resources/db/migration`).

### Variables de entorno (`.env`)

| Variable | Descripción |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Conexión a PostgreSQL |
| `JWT_SECRET` | Clave para firmar los JWT (≥ 32 caracteres). Generar una con `openssl rand -base64 48` |
| `RESEND_API_KEY` | API key de Resend. **Si está vacía**, los correos no se envían y los enlaces (verificación, reset, invitación) se imprimen en el log de la API para poder probar en local |
| `MAIL_FROM` | Remitente. Sin dominio verificado en Resend solo funciona `onboarding@resend.dev` y únicamente hacia tu propio correo |
| `FRONTEND_URL` | Origen permitido por CORS y base de los enlaces de los correos (por defecto `http://localhost:5173`) |

El archivo `.env` está en `.gitignore`; nunca se suben secretos.

### Pruebas

```bash
./mvnw test
```

Pruebas unitarias de `AuthService`, `IssueService` (código del issue y orden del Kanban),
`WorkspaceSecurity` (permisos), `SortOrderCalculator` y el avance de ciclos.

## Arquitectura

```
Request → RateLimitFilter → JwtAuthFilter → Controller → Service → Repository → PostgreSQL
```

- **Controller**: recibe y valida DTOs (`record`), nunca toca entidades ni repositorios.
- **Service** (interfaz + `impl/`): lógica de negocio, permisos y `@Transactional`.
- **Repository**: Spring Data JPA (+ `Specification` para los filtros de issues).
- **Mapper**: MapStruct convierte entidades a DTOs.
- Permisos por workspace con `@PreAuthorize("@workspaceSecurity.isAdmin(#slug)")`; el rol se
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

Roles: `OWNER` (creador), `ADMIN`, `MEMBER`. Cada workspace se identifica por su **slug** en la URL.
Algunos slugs están reservados porque chocan con rutas del frontend (`login`, `register`, `profile`,
`notifications`, `onboarding`, `invitations`, `settings`…); el backend responde 400 si se usan.

| Método | Ruta | Permiso |
|---|---|---|
| GET / POST | `/workspaces` | Mis workspaces (con `myRole`) / crear (`{ name, slug? }`) |
| GET | `/workspaces/{slug}` | Miembro |
| PATCH | `/workspaces/{slug}` | Admin |
| DELETE | `/workspaces/{slug}` | Owner |
| GET | `/workspaces/{slug}/members` | Miembro |
| PATCH | `/workspaces/{slug}/members/{userId}` | Admin (`{ role: "ADMIN" \| "MEMBER" }`) |
| DELETE | `/workspaces/{slug}/members/{userId}` | Admin, o uno mismo para salir |
| POST / GET | `/workspaces/{slug}/invitations` | Admin: invitar (`{ email, role }`) / pendientes |
| DELETE | `/workspaces/{slug}/invitations/{invitationId}` | Admin: cancelar |
| GET | `/invitations/{token}` | Ver invitación (**público**, sin sesión) |
| POST | `/invitations/{token}/accept` | Aceptar (con sesión del email invitado) |

### Proyectos, estados y etiquetas

Base: `/workspaces/{slug}/projects`. Un proyecto lo ven los **admins del workspace** y sus
**miembros**. Al crearlo se generan los estados Backlog, Todo, In Progress, Done y Cancelled.

| Método | Ruta | Descripción |
|---|---|---|
| GET / POST | `/projects` | Listar / crear (`{ name, identifier, description? }`; identifier ej. `WEB`) |
| GET / PATCH / DELETE | `/projects/{projectId}` | Ver / editar / eliminar (solo admin) |
| GET / POST | `/projects/{projectId}/members` | Miembros / agregar (`{ userId }`, debe estar en el workspace) |
| DELETE | `/projects/{projectId}/members/{userId}` | Quitar miembro |
| GET / POST | `/projects/{projectId}/states` | Estados (ordenados por `position`) / crear `{ name, color, group, position? }` |
| PATCH / DELETE | `/projects/{projectId}/states/{stateId}` | Editar / borrar (no si tiene issues) |
| GET / POST | `/projects/{projectId}/labels` | Etiquetas / crear `{ name, color }` |
| PATCH / DELETE | `/projects/{projectId}/labels/{labelId}` | Editar / borrar |

`group` ∈ `BACKLOG | UNSTARTED | STARTED | COMPLETED | CANCELLED`; los colores van como `#RRGGBB`.

### Issues

Base: `/workspaces/{slug}/projects/{projectId}/issues`.

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/issues` | Listado paginado con filtros |
| POST | `/issues` | Crear |
| GET / PATCH / DELETE | `/issues/{issueId}` | Ver / editar / eliminar |
| PATCH | `/issues/{issueId}/move` | Mover en el Kanban |
| GET | `/issues/{issueId}/sub-issues` | Sub-tareas |
| GET / POST | `/issues/{issueId}/comments` | Comentarios / comentar `{ body }` |
| PATCH / DELETE | `/issues/{issueId}/comments/{commentId}` | Editar (autor) / borrar (autor o admin) |
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
respuesta incluye también su ciclo (`cycle: { id, name }` o `null`) y sus módulos (`modules: [...]`).

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
| GET / POST | `/cycles` | Listar / crear `{ name, description?, startDate?, endDate? }` |
| GET / PATCH / DELETE | `/cycles/{cycleId}` | Ver / editar (`clearDates: true` lo vuelve borrador) / eliminar |
| POST | `/cycles/{cycleId}/issues` | Agregar `{ issueIds: [...] }` (un issue está en un solo ciclo: se mueve) |
| DELETE | `/cycles/{cycleId}/issues/{issueId}` | Quitar |
| GET / POST | `/modules` | Listar / crear `{ name, description?, startDate?, targetDate? }` |
| GET / PATCH / DELETE | `/modules/{moduleId}` | Ver / editar / eliminar |
| POST | `/modules/{moduleId}/issues` | Agregar `{ issueIds: [...] }` (un issue puede estar en varios módulos) |
| DELETE | `/modules/{moduleId}/issues/{issueId}` | Quitar |

Cada ciclo y módulo trae `progress: { totalIssues, completedIssues, percentage }` (completados =
issues en estados del grupo `COMPLETED`). Los ciclos traen además `status`:
`DRAFT | UPCOMING | CURRENT | COMPLETED`. Para listar los issues de un ciclo o módulo se usa el
filtro `?cycle=` o `?module=` del listado de issues.

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

Migraciones en `src/main/resources/db/migration` (`V1__users.sql` … `V6__cycles_modules.sql`).
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
