# Despliegue de TaskFlow en Dokploy

Guía para el primer despliegue (base vacía) y para los siguientes. TaskFlow son tres piezas dentro de Dokploy, y solo
el frontend tiene dominio público:

| Pieza | En Dokploy | Repositorio | Puerto |
|---|---|---|---|
| Base de datos | **Database → PostgreSQL 16** | — | 5432 (interno) |
| API | **Application** con build *Dockerfile* | `Server-IA/Task_backend`, rama `main` | 8080 (interno) |
| Frontend | **Application** con build *Dockerfile* y el dominio | `Server-IA/Task_frontend`, rama `main` | 5446 (dominio) |

El navegador solo habla con el frontend (`https://<dominio>`). Su nginx sirve la app y reenvía todo lo que empieza por
`/api` a la API dentro de la red de Dokploy, así la app y la API comparten dominio (sin CORS) y la API no queda
expuesta. GitHub también entra por ahí (`https://<dominio>/api/v1/github/...`).

## 1. Base de datos

1. *Create Service → Database → PostgreSQL*, versión 16. Anota el **host interno** (el *App Name* que muestra
   Dokploy), la base, el usuario y la contraseña.
2. No hace falta crear tablas: la API las crea al arrancar (Flyway aplica `V1__esquema_inicial.sql`, que trae todo el
   esquema en una sola migración).
3. Respaldos: en *Backups*, un destino S3 apuntando a R2 (endpoint `https://<R2_ACCOUNT_ID>.r2.cloudflarestorage.com`,
   región `auto`, un bucket aparte para respaldos, p. ej. `taskflow-backups`) y un respaldo diario.

## 2. API (`Task_backend`)

1. *Create Service → Application*, proveedor GitHub, repositorio `Task_backend`, rama `main`, build type
   **Dockerfile** (el de la raíz). Sin dominio: es interna.
2. *Environment* (todo lo demás tiene buen valor por defecto en la imagen):

| Variable | Valor |
|---|---|
| `DB_URL` | `jdbc:postgresql://<host interno de la base>:5432/<base>` |
| `DB_USER` / `DB_PASSWORD` | Los de la base |
| `JWT_SECRET` | Aleatorio y largo: `openssl rand -base64 48` |
| `FRONTEND_URL` | `https://<dominio>` (sin `/` al final). Base de los enlaces de los correos y único origen permitido |
| `MAIL_PROVIDER` | `smtp` (Gmail, ver más abajo) o `resend` (con dominio verificado y `RESEND_API_KEY`) |
| `MAIL_FROM` | `TaskFlow <cuenta@gmail.com>` (con Gmail, la misma cuenta que envía) |
| `SMTP_HOST` / `SMTP_PORT` | `smtp.gmail.com` / `587` |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | La cuenta de Gmail y una **contraseña de aplicación** |
| `SMTP_AUTH` / `SMTP_STARTTLS` | `true` / `true` |
| `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET` | Las del bucket de producción |
| `GITHUB_*` | Las seis de la GitHub App de producción (sección 5); la clave en `GITHUB_PRIVATE_KEY` |

Ya vienen puestas en la imagen (no hace falta tocarlas): `FORWARD_HEADERS_STRATEGY=native` (la IP real del cliente
llega de Traefik), `SPRINGDOC_ENABLED=false` (sin Swagger público) y la memoria de la JVM ajustada al contenedor.

3. *Deploy*. La imagen se marca sana cuando `GET /api/v1/health` responde `{"status":"ok"}` (llega a la base). El
   primer arranque tarda un poco más porque crea el esquema. Anota el **App Name** de la API: es su nombre dentro de
   la red (lo usa el frontend).

## 3. Frontend (`Task_frontend`)

1. *Create Service → Application*, repositorio `Task_frontend`, rama `main`, build type **Dockerfile**.
2. *Environment*: `BACKEND_URL=http://<App Name de la API>:8080`.
3. *Domains*: el dominio, **puerto del contenedor 5446**, HTTPS con Let's Encrypt.
4. *Deploy* y abre `https://<dominio>`: la primera persona que se registra crea su workspace y queda como project
   manager.

nginx sirve la app con caché larga para `/assets`, cabeceras de seguridad (nosniff, sin iframes, HSTS) y acepta
subidas de hasta 20 MB (la importación); los adjuntos e imágenes no pasan por aquí: van directo a R2.

## 4. Correo con Gmail (la cuenta exclusiva de TaskFlow)

1. En esa cuenta, activa la verificación en dos pasos y crea una **contraseña de aplicación**
   (Cuenta de Google → Seguridad → Contraseñas de aplicaciones). Esa va en `SMTP_PASSWORD`, no la contraseña normal.
2. `MAIL_FROM` tiene que ser esa misma dirección. Gmail envía unos 500 correos al día; para más, o para que salgan con
   el dominio propio, se cambia a Resend con el dominio verificado (SPF y DKIM).

## 5. GitHub App de producción

La app de desarrollo (*TaskFlow Dev Kaaapo*) apunta al túnel local; producción lleva una propia. En
*GitHub → Settings → Developer settings → GitHub Apps → New GitHub App* (o en la organización):

| Campo | Valor |
|---|---|
| Homepage URL | `https://<dominio>` |
| Callback URL | `https://<dominio>/github/callback` · **Expire user authorization tokens** activado · *Request user authorization (OAuth) during installation* **apagado** |
| Setup URL | `https://<dominio>/api/v1/github/setup` (sin *Redirect on update*) |
| Webhook | Activo, URL `https://<dominio>/api/v1/github/webhook`, un secreto aleatorio (va en `GITHUB_WEBHOOK_SECRET`) |
| Permisos del repositorio | Actions: *Read & write* · Checks: *Read* · Contents: *Read & write* · Metadata: *Read* · Pull requests: *Read* · Commit statuses: *Read* |
| Permisos de la organización | Members: *Read* (para comprobar que quien conecta una organización es dueño) |
| Eventos | Check suite · Create · Delete · Pull request · Workflow run |
| ¿Dónde se instala? | *Any account* si otros equipos la van a usar; si no, *Only on this account* |

Luego: *Generate a private key* (descarga un `.pem`) y *Generate a new client secret*. Variables:

| Variable | De dónde |
|---|---|
| `GITHUB_APP_ID` | App ID (arriba en la página de la app) |
| `GITHUB_APP_SLUG` | El nombre en la dirección `github.com/apps/<slug>` |
| `GITHUB_PRIVATE_KEY` | El contenido del `.pem` en una línea, con `\n` donde van los saltos (p. ej. `awk 'NF {printf "%s\\n", $0}' clave.pem`) |
| `GITHUB_WEBHOOK_SECRET` | El secreto del webhook |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | Client ID y el client secret generado |

## 6. R2 (archivos)

El bucket es privado; el navegador sube y descarga directo con URLs firmadas, así que su CORS tiene que permitir el
dominio de producción (*R2 → bucket → Settings → CORS policy*):

```json
[
  {
    "AllowedOrigins": ["https://<dominio>"],
    "AllowedMethods": ["GET", "PUT", "HEAD"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3600
  }
]
```

## 7. Comprobar

- `https://<dominio>/api/v1/health` → `{"status":"ok"}`.
- Registrarse, verificar el correo (llega de la cuenta de Gmail), crear un proyecto y una tarea, subir una imagen.
- *Ajustes del workspace → Integraciones → Conectar con GitHub* y un PR de prueba que nombre una tarea.

## Actualizaciones

- Lo que llega a `main` se despliega con *Deploy* en cada aplicación (o automático con *Autodeploy* / el webhook de
  Dokploy).
- Cambios de la base: siempre una migración nueva (`V2__…`, `V3__…`); `V1__esquema_inicial.sql` no se vuelve a
  editar. Flyway las aplica al arrancar la API.
- El túnel de Cloudflare (`Escritorio/taskflow-tunel`) solo servía para probar sin despliegue: ya no hace falta.
