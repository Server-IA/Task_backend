-- Esquema inicial de TaskFlow: todas las tablas, índices, restricciones, funciones y disparadores en una sola
-- migración. Reúne las migraciones V1–V37 del desarrollo (se unificaron antes del primer despliegue: la base de
-- producción empieza vacía). Generado con pg_dump --schema-only sobre una base con esas 37 migraciones aplicadas.
-- Los cambios que vengan van en migraciones nuevas (V2, V3…); esta no se vuelve a editar.
-- check_function_bodies: las funciones SQL nombran tablas que se crean más abajo (pg_dump las pone primero).

SET check_function_bodies = false;

CREATE FUNCTION public.queue_storage_deletion() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO storage_deletions (object_key) VALUES (OLD.object_key);
    RETURN OLD;
END;
$$;

CREATE FUNCTION public.sees_all_projects(p_workspace_id uuid, p_user_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
SELECT EXISTS (SELECT 1
               FROM workspace_members wm
                        JOIN workspaces w ON w.id = wm.workspace_id
                        LEFT JOIN workspace_roles r ON r.id = wm.custom_role_id
               WHERE wm.workspace_id = p_workspace_id
                 AND wm.user_id = p_user_id
                 AND (wm.role = 'OWNER'
                   OR COALESCE(r.permissions, w.member_permissions) @> '["PROJECT_VIEW_ALL"]'::jsonb))
$$;

CREATE TABLE public.comments (
    id uuid NOT NULL,
    issue_id uuid NOT NULL,
    author_id uuid NOT NULL,
    body text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.cycle_issues (
    cycle_id uuid NOT NULL,
    issue_id uuid NOT NULL
);

CREATE TABLE public.cycles (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    name character varying(80) NOT NULL,
    description text,
    start_date date,
    end_date date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    closed_at timestamp with time zone,
    closed_by_id uuid,
    carried_to_cycle integer DEFAULT 0 NOT NULL,
    carried_to_backlog integer DEFAULT 0 NOT NULL,
    retro_started_at timestamp with time zone,
    retro_finished_at timestamp with time zone,
    closed_completed_count integer,
    closed_total_count integer,
    closed_cycle_time_days double precision,
    CONSTRAINT ck_cycles_dates CHECK (((start_date IS NULL) OR (end_date IS NULL) OR (end_date >= start_date))),
    CONSTRAINT ck_cycles_retro CHECK (((retro_finished_at IS NULL) OR (retro_started_at IS NOT NULL)))
);

CREATE TABLE public.daily_notes (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    note_date date NOT NULL,
    text text NOT NULL,
    updated_by_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.daily_settings (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    show_summary boolean DEFAULT true NOT NULL,
    show_yesterday boolean DEFAULT true NOT NULL,
    show_today boolean DEFAULT true NOT NULL,
    show_blockers boolean DEFAULT true NOT NULL,
    show_note boolean DEFAULT true NOT NULL,
    agenda jsonb DEFAULT '[]'::jsonb NOT NULL,
    updated_by_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.github_accounts (
    user_id uuid NOT NULL,
    github_id bigint NOT NULL,
    login character varying(100) NOT NULL,
    avatar_url character varying(500),
    access_token text NOT NULL,
    refresh_token text,
    access_expires_at timestamp with time zone,
    refresh_expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.github_branches (
    id uuid NOT NULL,
    repository_id uuid NOT NULL,
    issue_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.github_installations (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    installation_id bigint NOT NULL,
    account_login character varying(100) NOT NULL,
    account_type character varying(20) NOT NULL,
    installed_by_id uuid,
    last_event_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.github_project_settings (
    project_id uuid NOT NULL,
    on_branch_state_id uuid,
    on_pr_opened_state_id uuid,
    on_pr_merged_state_id uuid,
    on_pr_closed_state_id uuid,
    branch_format character varying(20) DEFAULT 'CODE_TITLE'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_github_project_settings_branch CHECK (((branch_format)::text = ANY ((ARRAY['CODE_TITLE'::character varying, 'TYPE_CODE_TITLE'::character varying, 'CODE'::character varying])::text[])))
);

CREATE TABLE public.github_pull_requests (
    id uuid NOT NULL,
    repository_id uuid NOT NULL,
    issue_id uuid,
    number integer NOT NULL,
    title character varying(500) NOT NULL,
    state character varying(10) NOT NULL,
    url character varying(500) NOT NULL,
    author_login character varying(100),
    head_branch character varying(255),
    base_branch character varying(255),
    head_sha character varying(64),
    reviewers character varying(500),
    checks_state character varying(10),
    checks_total integer DEFAULT 0 NOT NULL,
    checks_failed integer DEFAULT 0 NOT NULL,
    merged_at timestamp with time zone,
    opened_at timestamp with time zone,
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    issue_unlinked boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_github_pull_requests_checks CHECK (((checks_state IS NULL) OR ((checks_state)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying, 'PENDING'::character varying])::text[])))),
    CONSTRAINT ck_github_pull_requests_state CHECK (((state)::text = ANY ((ARRAY['OPEN'::character varying, 'DRAFT'::character varying, 'MERGED'::character varying, 'CLOSED'::character varying])::text[])))
);

CREATE TABLE public.github_repositories (
    id uuid NOT NULL,
    installation_id uuid NOT NULL,
    github_id bigint NOT NULL,
    full_name character varying(200) NOT NULL,
    default_branch character varying(255),
    is_private boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.github_rules (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    fires_on character varying(20) NOT NULL,
    repository_id uuid NOT NULL,
    workflow_id bigint NOT NULL,
    workflow_name character varying(200) NOT NULL,
    ref character varying(255) NOT NULL,
    created_by_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_github_rules_fires_on CHECK (((fires_on)::text = ANY ((ARRAY['PR_MERGED'::character varying, 'ISSUE_COMPLETED'::character varying])::text[])))
);

CREATE TABLE public.github_webhook_deliveries (
    delivery_id character varying(80) NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.github_workflow_runs (
    id uuid NOT NULL,
    repository_id uuid NOT NULL,
    run_id bigint,
    workflow_id bigint,
    workflow_name character varying(200),
    title character varying(500),
    branch character varying(255),
    head_sha character varying(64),
    event character varying(40),
    status character varying(20) NOT NULL,
    conclusion character varying(20),
    actor_login character varying(100),
    url character varying(500),
    pr_number integer,
    issue_id uuid,
    rule_id uuid,
    triggered_by_id uuid,
    requested_at timestamp with time zone,
    started_at timestamp with time zone,
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.import_items (
    id bigint NOT NULL,
    job_id uuid NOT NULL,
    kind character varying(20) NOT NULL,
    entity_id uuid NOT NULL,
    CONSTRAINT ck_import_items_kind CHECK (((kind)::text = ANY ((ARRAY['PROJECT'::character varying, 'STATE'::character varying, 'LABEL'::character varying, 'MODULE'::character varying, 'CYCLE'::character varying, 'ISSUE'::character varying])::text[])))
);

CREATE SEQUENCE public.import_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.import_items_id_seq OWNED BY public.import_items.id;

CREATE TABLE public.import_jobs (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    created_by_id uuid,
    source character varying(20) NOT NULL,
    via character varying(10) NOT NULL,
    source_label character varying(200) NOT NULL,
    status character varying(20) NOT NULL,
    discovery text,
    selection text,
    mapping text,
    progress text,
    report text,
    error character varying(500),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_import_jobs_source CHECK (((source)::text = ANY ((ARRAY['JIRA'::character varying, 'TRELLO'::character varying, 'LINEAR'::character varying, 'ASANA'::character varying, 'CSV'::character varying])::text[]))),
    CONSTRAINT ck_import_jobs_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'FETCHING'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'STOPPED'::character varying, 'UNDOING'::character varying, 'UNDONE'::character varying])::text[]))),
    CONSTRAINT ck_import_jobs_via CHECK (((via)::text = ANY ((ARRAY['API'::character varying, 'FILE'::character varying])::text[])))
);

CREATE TABLE public.import_payloads (
    job_id uuid NOT NULL,
    kind character varying(10) NOT NULL,
    data bytea NOT NULL,
    CONSTRAINT ck_import_payloads_kind CHECK (((kind)::text = ANY ((ARRAY['UPLOAD'::character varying, 'BUNDLE'::character varying])::text[])))
);

CREATE TABLE public.import_pending_assignments (
    id bigint NOT NULL,
    workspace_id uuid NOT NULL,
    email character varying(255) NOT NULL,
    issue_id uuid NOT NULL
);

CREATE SEQUENCE public.import_pending_assignments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.import_pending_assignments_id_seq OWNED BY public.import_pending_assignments.id;

CREATE TABLE public.issue_activities (
    id uuid NOT NULL,
    issue_id uuid NOT NULL,
    actor_id uuid,
    field character varying(30) NOT NULL,
    old_value text,
    new_value text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    file_id uuid
);

CREATE TABLE public.issue_assignees (
    issue_id uuid NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE public.issue_checklist_items (
    id uuid NOT NULL,
    issue_id uuid NOT NULL,
    kind character varying(10) NOT NULL,
    text character varying(500) NOT NULL,
    done boolean DEFAULT false NOT NULL,
    "position" integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    checked_by_id uuid,
    checked_at timestamp with time zone,
    CONSTRAINT ck_issue_checklist_items_kind CHECK (((kind)::text = ANY ((ARRAY['CRITERION'::character varying, 'CHECK'::character varying])::text[])))
);

CREATE TABLE public.issue_labels (
    issue_id uuid NOT NULL,
    label_id uuid NOT NULL
);

CREATE TABLE public.issue_relations (
    id uuid NOT NULL,
    source_issue_id uuid NOT NULL,
    target_issue_id uuid NOT NULL,
    type character varying(10) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_issue_relations_self CHECK ((source_issue_id <> target_issue_id)),
    CONSTRAINT ck_issue_relations_type CHECK (((type)::text = ANY ((ARRAY['BLOCKS'::character varying, 'RELATES'::character varying])::text[])))
);

CREATE TABLE public.issue_template_assignees (
    template_id uuid NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE public.issue_template_items (
    template_id uuid NOT NULL,
    "position" integer NOT NULL,
    kind character varying(10) NOT NULL,
    text character varying(500) NOT NULL,
    CONSTRAINT ck_issue_template_items_kind CHECK (((kind)::text = ANY ((ARRAY['CRITERION'::character varying, 'CHECK'::character varying])::text[])))
);

CREATE TABLE public.issue_template_labels (
    template_id uuid NOT NULL,
    label_id uuid NOT NULL
);

CREATE TABLE public.issue_templates (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    name character varying(60) NOT NULL,
    title_prefix character varying(40),
    description text,
    priority character varying(10),
    "position" integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    usage_count integer DEFAULT 0 NOT NULL,
    state_id uuid,
    issue_type character varying(10) DEFAULT 'TASK'::character varying NOT NULL,
    CONSTRAINT ck_issue_templates_priority CHECK (((priority)::text = ANY ((ARRAY['URGENT'::character varying, 'HIGH'::character varying, 'MEDIUM'::character varying, 'LOW'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT ck_issue_templates_type CHECK (((issue_type)::text = ANY ((ARRAY['STORY'::character varying, 'TASK'::character varying, 'BUG'::character varying])::text[])))
);

CREATE TABLE public.issues (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    sequence_id integer NOT NULL,
    title character varying(255) NOT NULL,
    description text,
    state_id uuid NOT NULL,
    priority character varying(10) DEFAULT 'NONE'::character varying NOT NULL,
    parent_id uuid,
    start_date date,
    due_date date,
    sort_order double precision NOT NULL,
    created_by_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    started_at timestamp with time zone,
    checklist_updated_at timestamp with time zone,
    description_version integer DEFAULT 0 NOT NULL,
    description_updated_at timestamp with time zone,
    description_updated_by_id uuid,
    issue_type character varying(10) DEFAULT 'TASK'::character varying NOT NULL,
    closed_at timestamp with time zone,
    closed_by_id uuid,
    review_notified boolean DEFAULT false NOT NULL,
    import_source character varying(20),
    import_key character varying(100),
    import_url character varying(1000),
    CONSTRAINT ck_issues_dates CHECK (((start_date IS NULL) OR (due_date IS NULL) OR (due_date >= start_date))),
    CONSTRAINT ck_issues_priority CHECK (((priority)::text = ANY ((ARRAY['URGENT'::character varying, 'HIGH'::character varying, 'MEDIUM'::character varying, 'LOW'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT ck_issues_type CHECK (((issue_type)::text = ANY ((ARRAY['STORY'::character varying, 'TASK'::character varying, 'BUG'::character varying])::text[])))
);

CREATE TABLE public.labels (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    name character varying(50) NOT NULL,
    color character varying(7) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.module_issues (
    module_id uuid NOT NULL,
    issue_id uuid NOT NULL
);

CREATE TABLE public.modules (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    name character varying(80) NOT NULL,
    description text,
    start_date date,
    target_date date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_modules_dates CHECK (((start_date IS NULL) OR (target_date IS NULL) OR (target_date >= start_date)))
);

CREATE TABLE public.notifications (
    id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    actor_id uuid,
    type character varying(30) NOT NULL,
    message character varying(500) NOT NULL,
    issue_id uuid,
    is_read boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    cycle_id uuid,
    comment_id uuid,
    workspace_id uuid,
    CONSTRAINT ck_notifications_type CHECK (((type)::text = ANY ((ARRAY['ISSUE_ASSIGNED'::character varying, 'ISSUE_COMMENTED'::character varying, 'ISSUE_UNBLOCKED'::character varying, 'ISSUE_MENTIONED'::character varying, 'RETRO_STARTED'::character varying, 'STORY_READY'::character varying, 'STORAGE_WARNING'::character varying, 'IMPORT_FINISHED'::character varying, 'PR_MERGED_PENDING'::character varying])::text[])))
);

CREATE TABLE public.project_members (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    user_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.project_repositories (
    project_id uuid NOT NULL,
    repository_id uuid NOT NULL
);

CREATE TABLE public.projects (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    name character varying(80) NOT NULL,
    identifier character varying(12) NOT NULL,
    description text,
    issue_counter integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    color character varying(12) NOT NULL,
    CONSTRAINT ck_projects_color CHECK (((color)::text = ANY ((ARRAY['TEAL'::character varying, 'AMBER'::character varying, 'BLUE'::character varying, 'PINK'::character varying, 'VIOLET'::character varying, 'ORANGE'::character varying, 'GREEN'::character varying])::text[])))
);

CREATE TABLE public.refresh_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    replaced_by uuid
);

CREATE TABLE public.retro_note_votes (
    note_id uuid NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE public.retro_notes (
    id uuid NOT NULL,
    cycle_id uuid NOT NULL,
    retro_column character varying(10) NOT NULL,
    text character varying(500) NOT NULL,
    author_id uuid NOT NULL,
    issue_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    owner_id uuid,
    CONSTRAINT ck_retro_notes_column CHECK (((retro_column)::text = ANY ((ARRAY['WENT_WELL'::character varying, 'IMPROVE'::character varying, 'ACTION'::character varying])::text[])))
);

CREATE TABLE public.saved_views (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    name character varying(40) NOT NULL,
    shared boolean DEFAULT false NOT NULL,
    filters jsonb DEFAULT '{}'::jsonb NOT NULL,
    "position" integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.states (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    name character varying(50) NOT NULL,
    color character varying(7) NOT NULL,
    state_group character varying(20) NOT NULL,
    "position" integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_states_group CHECK (((state_group)::text = ANY ((ARRAY['BACKLOG'::character varying, 'UNSTARTED'::character varying, 'STARTED'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))
);

CREATE TABLE public.storage_deletions (
    id bigint NOT NULL,
    object_key character varying(200) NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.storage_deletions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.storage_deletions_id_seq OWNED BY public.storage_deletions.id;

CREATE TABLE public.stored_files (
    id uuid NOT NULL,
    workspace_id uuid,
    project_id uuid,
    issue_id uuid,
    uploaded_by uuid,
    kind character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    object_key character varying(200) NOT NULL,
    file_name character varying(255) NOT NULL,
    content_type character varying(100) NOT NULL,
    size_bytes bigint NOT NULL,
    confirmed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_stored_files_attachment CHECK ((((kind)::text <> 'ATTACHMENT'::text) OR (issue_id IS NOT NULL))),
    CONSTRAINT ck_stored_files_kind CHECK (((kind)::text = ANY ((ARRAY['ATTACHMENT'::character varying, 'INLINE_IMAGE'::character varying, 'AVATAR'::character varying])::text[]))),
    CONSTRAINT ck_stored_files_scope CHECK (((((kind)::text = 'AVATAR'::text) AND (workspace_id IS NULL) AND (project_id IS NULL) AND (issue_id IS NULL)) OR (((kind)::text <> 'AVATAR'::text) AND (workspace_id IS NOT NULL) AND (project_id IS NOT NULL)))),
    CONSTRAINT ck_stored_files_size CHECK ((size_bytes > 0)),
    CONSTRAINT ck_stored_files_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'READY'::character varying])::text[])))
);

CREATE TABLE public.users (
    id uuid NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    full_name character varying(120) NOT NULL,
    avatar_url character varying(500),
    email_verified boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    credentials_changed_at timestamp with time zone,
    credentials_version integer DEFAULT 0 NOT NULL,
    language character varying(5),
    CONSTRAINT ck_users_language CHECK (((language)::text = ANY ((ARRAY['es'::character varying, 'en'::character varying])::text[])))
);

CREATE TABLE public.verification_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    type character varying(30) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_verification_tokens_type CHECK (((type)::text = ANY ((ARRAY['EMAIL_VERIFICATION'::character varying, 'PASSWORD_RESET'::character varying])::text[])))
);

CREATE TABLE public.workspace_invitations (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    email character varying(255) NOT NULL,
    role character varying(20) NOT NULL,
    token_hash character varying(64) NOT NULL,
    invited_by_id uuid,
    expires_at timestamp with time zone NOT NULL,
    accepted boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    custom_role_id uuid,
    CONSTRAINT ck_workspace_invitations_role CHECK (((role)::text = 'MEMBER'::text))
);

CREATE TABLE public.workspace_members (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    custom_role_id uuid,
    CONSTRAINT ck_workspace_members_custom_role CHECK (((custom_role_id IS NULL) OR ((role)::text = 'MEMBER'::text))),
    CONSTRAINT ck_workspace_members_role CHECK (((role)::text = ANY ((ARRAY['OWNER'::character varying, 'MEMBER'::character varying])::text[])))
);

CREATE TABLE public.workspace_roles (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    name character varying(40) NOT NULL,
    description character varying(200),
    permissions jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.workspaces (
    id uuid NOT NULL,
    name character varying(80) NOT NULL,
    slug character varying(48) NOT NULL,
    owner_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    member_permissions jsonb DEFAULT '["ISSUE_CREATE", "ISSUE_EDIT", "ISSUE_DELETE_OWN", "ISSUE_CHECKLIST", "ISSUE_RELATIONS", "ISSUE_ATTACH", "COMMENT_CREATE", "COMMENT_IMAGES", "CYCLE_ASSIGN", "RETRO_PARTICIPATE", "MODULE_ASSIGN", "DAILY_NOTE", "STATS_VIEW", "REPORT_GENERATE", "ACTIVITY_VIEW"]'::jsonb NOT NULL
);

ALTER TABLE ONLY public.import_items ALTER COLUMN id SET DEFAULT nextval('public.import_items_id_seq'::regclass);

ALTER TABLE ONLY public.import_pending_assignments ALTER COLUMN id SET DEFAULT nextval('public.import_pending_assignments_id_seq'::regclass);

ALTER TABLE ONLY public.storage_deletions ALTER COLUMN id SET DEFAULT nextval('public.storage_deletions_id_seq'::regclass);

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cycle_issues
    ADD CONSTRAINT cycle_issues_pkey PRIMARY KEY (cycle_id, issue_id);

ALTER TABLE ONLY public.cycles
    ADD CONSTRAINT cycles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.daily_notes
    ADD CONSTRAINT daily_notes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.daily_settings
    ADD CONSTRAINT daily_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.github_accounts
    ADD CONSTRAINT github_accounts_pkey PRIMARY KEY (user_id);

ALTER TABLE ONLY public.github_branches
    ADD CONSTRAINT github_branches_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.github_installations
    ADD CONSTRAINT github_installations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.github_project_settings
    ADD CONSTRAINT github_project_settings_pkey PRIMARY KEY (project_id);

ALTER TABLE ONLY public.github_pull_requests
    ADD CONSTRAINT github_pull_requests_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.github_repositories
    ADD CONSTRAINT github_repositories_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.github_rules
    ADD CONSTRAINT github_rules_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.github_webhook_deliveries
    ADD CONSTRAINT github_webhook_deliveries_pkey PRIMARY KEY (delivery_id);

ALTER TABLE ONLY public.github_workflow_runs
    ADD CONSTRAINT github_workflow_runs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.import_items
    ADD CONSTRAINT import_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.import_jobs
    ADD CONSTRAINT import_jobs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.import_payloads
    ADD CONSTRAINT import_payloads_pkey PRIMARY KEY (job_id, kind);

ALTER TABLE ONLY public.import_pending_assignments
    ADD CONSTRAINT import_pending_assignments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_activities
    ADD CONSTRAINT issue_activities_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_assignees
    ADD CONSTRAINT issue_assignees_pkey PRIMARY KEY (issue_id, user_id);

ALTER TABLE ONLY public.issue_checklist_items
    ADD CONSTRAINT issue_checklist_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_labels
    ADD CONSTRAINT issue_labels_pkey PRIMARY KEY (issue_id, label_id);

ALTER TABLE ONLY public.issue_relations
    ADD CONSTRAINT issue_relations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_template_assignees
    ADD CONSTRAINT issue_template_assignees_pkey PRIMARY KEY (template_id, user_id);

ALTER TABLE ONLY public.issue_template_items
    ADD CONSTRAINT issue_template_items_pkey PRIMARY KEY (template_id, "position");

ALTER TABLE ONLY public.issue_template_labels
    ADD CONSTRAINT issue_template_labels_pkey PRIMARY KEY (template_id, label_id);

ALTER TABLE ONLY public.issue_templates
    ADD CONSTRAINT issue_templates_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issues
    ADD CONSTRAINT issues_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.labels
    ADD CONSTRAINT labels_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.module_issues
    ADD CONSTRAINT module_issues_pkey PRIMARY KEY (module_id, issue_id);

ALTER TABLE ONLY public.modules
    ADD CONSTRAINT modules_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.project_members
    ADD CONSTRAINT project_members_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.project_repositories
    ADD CONSTRAINT project_repositories_pkey PRIMARY KEY (project_id, repository_id);

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.retro_note_votes
    ADD CONSTRAINT retro_note_votes_pkey PRIMARY KEY (note_id, user_id);

ALTER TABLE ONLY public.retro_notes
    ADD CONSTRAINT retro_notes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.saved_views
    ADD CONSTRAINT saved_views_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.states
    ADD CONSTRAINT states_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.storage_deletions
    ADD CONSTRAINT storage_deletions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.stored_files
    ADD CONSTRAINT stored_files_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cycle_issues
    ADD CONSTRAINT uk_cycle_issues_issue UNIQUE (issue_id);

ALTER TABLE ONLY public.daily_notes
    ADD CONSTRAINT uk_daily_notes_day UNIQUE (project_id, note_date);

ALTER TABLE ONLY public.daily_settings
    ADD CONSTRAINT uk_daily_settings_project UNIQUE (project_id);

ALTER TABLE ONLY public.github_accounts
    ADD CONSTRAINT uk_github_accounts_github UNIQUE (github_id);

ALTER TABLE ONLY public.github_branches
    ADD CONSTRAINT uk_github_branches UNIQUE (repository_id, name, issue_id);

ALTER TABLE ONLY public.github_installations
    ADD CONSTRAINT uk_github_installations_installation UNIQUE (installation_id);

ALTER TABLE ONLY public.github_installations
    ADD CONSTRAINT uk_github_installations_workspace UNIQUE (workspace_id);

ALTER TABLE ONLY public.github_pull_requests
    ADD CONSTRAINT uk_github_pull_requests UNIQUE (repository_id, number);

ALTER TABLE ONLY public.github_repositories
    ADD CONSTRAINT uk_github_repositories UNIQUE (installation_id, github_id);

ALTER TABLE ONLY public.github_workflow_runs
    ADD CONSTRAINT uk_github_workflow_runs UNIQUE (repository_id, run_id);

ALTER TABLE ONLY public.import_pending_assignments
    ADD CONSTRAINT uk_import_pending_assignments UNIQUE (issue_id, email);

ALTER TABLE ONLY public.issues
    ADD CONSTRAINT uk_issues_sequence UNIQUE (project_id, sequence_id);

ALTER TABLE ONLY public.labels
    ADD CONSTRAINT uk_labels_name UNIQUE (project_id, name);

ALTER TABLE ONLY public.project_members
    ADD CONSTRAINT uk_project_members UNIQUE (project_id, user_id);

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT uk_projects_identifier UNIQUE (workspace_id, identifier);

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash);

ALTER TABLE ONLY public.states
    ADD CONSTRAINT uk_states_name UNIQUE (project_id, name);

ALTER TABLE ONLY public.stored_files
    ADD CONSTRAINT uk_stored_files_key UNIQUE (object_key);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk_users_email UNIQUE (email);

ALTER TABLE ONLY public.verification_tokens
    ADD CONSTRAINT uk_verification_tokens_hash UNIQUE (token_hash);

ALTER TABLE ONLY public.workspace_invitations
    ADD CONSTRAINT uk_workspace_invitations_hash UNIQUE (token_hash);

ALTER TABLE ONLY public.workspace_members
    ADD CONSTRAINT uk_workspace_members UNIQUE (workspace_id, user_id);

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT uk_workspaces_slug UNIQUE (slug);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.verification_tokens
    ADD CONSTRAINT verification_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_invitations
    ADD CONSTRAINT workspace_invitations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_members
    ADD CONSTRAINT workspace_members_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_roles
    ADD CONSTRAINT workspace_roles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_pkey PRIMARY KEY (id);

CREATE INDEX idx_comments_author ON public.comments USING btree (author_id);

CREATE INDEX idx_comments_issue ON public.comments USING btree (issue_id, created_at);

CREATE INDEX idx_cycles_project ON public.cycles USING btree (project_id);

CREATE INDEX idx_github_accounts_login ON public.github_accounts USING btree (lower((login)::text));

CREATE INDEX idx_github_branches_issue ON public.github_branches USING btree (issue_id);

CREATE INDEX idx_github_pull_requests_changed ON public.github_pull_requests USING btree (repository_id, changed_at DESC);

CREATE INDEX idx_github_pull_requests_issue ON public.github_pull_requests USING btree (issue_id);

CREATE INDEX idx_github_pull_requests_sha ON public.github_pull_requests USING btree (repository_id, head_sha);

CREATE INDEX idx_github_rules_project ON public.github_rules USING btree (project_id);

CREATE INDEX idx_github_workflow_runs_issue ON public.github_workflow_runs USING btree (issue_id);

CREATE INDEX idx_github_workflow_runs_repository ON public.github_workflow_runs USING btree (repository_id, changed_at DESC);

CREATE INDEX idx_github_workflow_runs_rule ON public.github_workflow_runs USING btree (rule_id) WHERE (rule_id IS NOT NULL);

CREATE INDEX idx_import_items_job ON public.import_items USING btree (job_id, kind);

CREATE INDEX idx_import_jobs_workspace ON public.import_jobs USING btree (workspace_id, created_at DESC);

CREATE INDEX idx_import_pending_assignments_email ON public.import_pending_assignments USING btree (workspace_id, email);

CREATE INDEX idx_issue_activities_actor ON public.issue_activities USING btree (actor_id, created_at);

CREATE INDEX idx_issue_activities_created ON public.issue_activities USING btree (created_at);

CREATE INDEX idx_issue_activities_file ON public.issue_activities USING btree (file_id) WHERE (file_id IS NOT NULL);

CREATE INDEX idx_issue_activities_issue ON public.issue_activities USING btree (issue_id, created_at);

CREATE INDEX idx_issue_assignees_user ON public.issue_assignees USING btree (user_id);

CREATE INDEX idx_issue_checklist_items_checked_by ON public.issue_checklist_items USING btree (checked_by_id) WHERE (checked_by_id IS NOT NULL);

CREATE INDEX idx_issue_checklist_items_issue ON public.issue_checklist_items USING btree (issue_id, kind, "position");

CREATE INDEX idx_issue_labels_label ON public.issue_labels USING btree (label_id);

CREATE INDEX idx_issue_relations_source ON public.issue_relations USING btree (source_issue_id, type);

CREATE INDEX idx_issue_relations_target ON public.issue_relations USING btree (target_issue_id, type);

CREATE INDEX idx_issue_template_assignees_user ON public.issue_template_assignees USING btree (user_id);

CREATE INDEX idx_issue_template_labels_label ON public.issue_template_labels USING btree (label_id);

CREATE INDEX idx_issue_templates_state ON public.issue_templates USING btree (state_id) WHERE (state_id IS NOT NULL);

CREATE INDEX idx_issues_board ON public.issues USING btree (project_id, state_id, sort_order);

CREATE INDEX idx_issues_completed ON public.issues USING btree (project_id, completed_at) WHERE (completed_at IS NOT NULL);

CREATE INDEX idx_issues_created_by ON public.issues USING btree (created_by_id);

CREATE INDEX idx_issues_parent ON public.issues USING btree (parent_id);

CREATE INDEX idx_issues_project_type ON public.issues USING btree (project_id, issue_type);

CREATE INDEX idx_issues_started ON public.issues USING btree (project_id, started_at) WHERE (started_at IS NOT NULL);

CREATE INDEX idx_issues_state_sort ON public.issues USING btree (state_id, sort_order);

CREATE INDEX idx_module_issues_issue ON public.module_issues USING btree (issue_id);

CREATE INDEX idx_modules_project ON public.modules USING btree (project_id);

CREATE INDEX idx_notifications_actor ON public.notifications USING btree (actor_id) WHERE (actor_id IS NOT NULL);

CREATE INDEX idx_notifications_comment ON public.notifications USING btree (comment_id) WHERE (comment_id IS NOT NULL);

CREATE INDEX idx_notifications_cycle ON public.notifications USING btree (cycle_id) WHERE (cycle_id IS NOT NULL);

CREATE INDEX idx_notifications_issue ON public.notifications USING btree (issue_id) WHERE (issue_id IS NOT NULL);

CREATE INDEX idx_notifications_read_created ON public.notifications USING btree (created_at) WHERE is_read;

CREATE INDEX idx_notifications_recipient ON public.notifications USING btree (recipient_id, is_read, created_at DESC);

CREATE INDEX idx_notifications_recipient_cursor ON public.notifications USING btree (recipient_id, created_at DESC, id DESC);

CREATE INDEX idx_notifications_recipient_type ON public.notifications USING btree (recipient_id, type, created_at DESC);

CREATE INDEX idx_notifications_workspace ON public.notifications USING btree (workspace_id) WHERE (workspace_id IS NOT NULL);

CREATE INDEX idx_project_members_user ON public.project_members USING btree (user_id);

CREATE INDEX idx_project_repositories_repository ON public.project_repositories USING btree (repository_id);

CREATE INDEX idx_refresh_tokens_expires ON public.refresh_tokens USING btree (expires_at);

CREATE INDEX idx_refresh_tokens_user ON public.refresh_tokens USING btree (user_id);

CREATE INDEX idx_retro_note_votes_user ON public.retro_note_votes USING btree (user_id);

CREATE INDEX idx_retro_notes_author ON public.retro_notes USING btree (author_id);

CREATE INDEX idx_retro_notes_cycle ON public.retro_notes USING btree (cycle_id, created_at);

CREATE INDEX idx_retro_notes_issue ON public.retro_notes USING btree (issue_id) WHERE (issue_id IS NOT NULL);

CREATE INDEX idx_retro_notes_owner ON public.retro_notes USING btree (owner_id) WHERE (owner_id IS NOT NULL);

CREATE INDEX idx_saved_views_owner ON public.saved_views USING btree (owner_id);

CREATE INDEX idx_saved_views_project ON public.saved_views USING btree (project_id, shared, "position");

CREATE INDEX idx_stored_files_issue ON public.stored_files USING btree (issue_id, created_at) WHERE (issue_id IS NOT NULL);

CREATE INDEX idx_stored_files_pending ON public.stored_files USING btree (created_at) WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX idx_stored_files_project ON public.stored_files USING btree (project_id) WHERE (project_id IS NOT NULL);

CREATE INDEX idx_stored_files_uploader ON public.stored_files USING btree (uploaded_by, kind);

CREATE INDEX idx_stored_files_workspace ON public.stored_files USING btree (workspace_id, size_bytes DESC) WHERE (workspace_id IS NOT NULL);

CREATE INDEX idx_verification_tokens_expires ON public.verification_tokens USING btree (expires_at);

CREATE INDEX idx_verification_tokens_user ON public.verification_tokens USING btree (user_id, type);

CREATE INDEX idx_workspace_invitations_custom_role ON public.workspace_invitations USING btree (custom_role_id);

CREATE INDEX idx_workspace_invitations_workspace ON public.workspace_invitations USING btree (workspace_id, email);

CREATE INDEX idx_workspace_members_custom_role ON public.workspace_members USING btree (custom_role_id);

CREATE INDEX idx_workspace_members_user ON public.workspace_members USING btree (user_id);

CREATE UNIQUE INDEX uk_issue_relations_pair ON public.issue_relations USING btree (LEAST(source_issue_id, target_issue_id), GREATEST(source_issue_id, target_issue_id), type);

CREATE UNIQUE INDEX uk_issue_templates_name ON public.issue_templates USING btree (project_id, lower((name)::text));

CREATE UNIQUE INDEX uk_workspace_roles_name ON public.workspace_roles USING btree (workspace_id, lower((name)::text));

CREATE TRIGGER trg_stored_files_deleted AFTER DELETE ON public.stored_files FOR EACH ROW EXECUTE FUNCTION public.queue_storage_deletion();

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_author_id_fkey FOREIGN KEY (author_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cycle_issues
    ADD CONSTRAINT cycle_issues_cycle_id_fkey FOREIGN KEY (cycle_id) REFERENCES public.cycles(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cycle_issues
    ADD CONSTRAINT cycle_issues_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cycles
    ADD CONSTRAINT cycles_closed_by_id_fkey FOREIGN KEY (closed_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.cycles
    ADD CONSTRAINT cycles_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.daily_notes
    ADD CONSTRAINT daily_notes_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.daily_notes
    ADD CONSTRAINT daily_notes_updated_by_id_fkey FOREIGN KEY (updated_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.daily_settings
    ADD CONSTRAINT daily_settings_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.daily_settings
    ADD CONSTRAINT daily_settings_updated_by_id_fkey FOREIGN KEY (updated_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.github_accounts
    ADD CONSTRAINT github_accounts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.github_branches
    ADD CONSTRAINT github_branches_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.github_branches
    ADD CONSTRAINT github_branches_repository_id_fkey FOREIGN KEY (repository_id) REFERENCES public.github_repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.github_installations
    ADD CONSTRAINT github_installations_installed_by_id_fkey FOREIGN KEY (installed_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.github_installations
    ADD CONSTRAINT github_installations_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.github_project_settings
    ADD CONSTRAINT github_project_settings_on_branch_state_id_fkey FOREIGN KEY (on_branch_state_id) REFERENCES public.states(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.github_project_settings
    ADD CONSTRAINT github_project_settings_on_pr_closed_state_id_fkey FOREIGN KEY (on_pr_closed_state_id) REFERENCES public.states(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.github_project_settings
    ADD CONSTRAINT github_project_settings_on_pr_merged_state_id_fkey FOREIGN KEY (on_pr_merged_state_id) REFERENCES public.states(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.github_project_settings
    ADD CONSTRAINT github_project_settings_on_pr_opened_state_id_fkey FOREIGN KEY (on_pr_opened_state_id) REFERENCES public.states(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.github_project_settings
    ADD CONSTRAINT github_project_settings_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.github_pull_requests
    ADD CONSTRAINT github_pull_requests_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.github_pull_requests
    ADD CONSTRAINT github_pull_requests_repository_id_fkey FOREIGN KEY (repository_id) REFERENCES public.github_repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.github_repositories
    ADD CONSTRAINT github_repositories_installation_id_fkey FOREIGN KEY (installation_id) REFERENCES public.github_installations(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.github_rules
    ADD CONSTRAINT github_rules_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.github_rules
    ADD CONSTRAINT github_rules_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.github_rules
    ADD CONSTRAINT github_rules_repository_id_fkey FOREIGN KEY (repository_id) REFERENCES public.github_repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.github_workflow_runs
    ADD CONSTRAINT github_workflow_runs_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.github_workflow_runs
    ADD CONSTRAINT github_workflow_runs_repository_id_fkey FOREIGN KEY (repository_id) REFERENCES public.github_repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.github_workflow_runs
    ADD CONSTRAINT github_workflow_runs_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES public.github_rules(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.github_workflow_runs
    ADD CONSTRAINT github_workflow_runs_triggered_by_id_fkey FOREIGN KEY (triggered_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.import_items
    ADD CONSTRAINT import_items_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.import_jobs(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.import_jobs
    ADD CONSTRAINT import_jobs_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.import_jobs
    ADD CONSTRAINT import_jobs_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.import_payloads
    ADD CONSTRAINT import_payloads_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.import_jobs(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.import_pending_assignments
    ADD CONSTRAINT import_pending_assignments_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.import_pending_assignments
    ADD CONSTRAINT import_pending_assignments_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_activities
    ADD CONSTRAINT issue_activities_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issue_activities
    ADD CONSTRAINT issue_activities_file_id_fkey FOREIGN KEY (file_id) REFERENCES public.stored_files(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issue_activities
    ADD CONSTRAINT issue_activities_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_assignees
    ADD CONSTRAINT issue_assignees_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_assignees
    ADD CONSTRAINT issue_assignees_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_checklist_items
    ADD CONSTRAINT issue_checklist_items_checked_by_id_fkey FOREIGN KEY (checked_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issue_checklist_items
    ADD CONSTRAINT issue_checklist_items_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_labels
    ADD CONSTRAINT issue_labels_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_labels
    ADD CONSTRAINT issue_labels_label_id_fkey FOREIGN KEY (label_id) REFERENCES public.labels(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_relations
    ADD CONSTRAINT issue_relations_source_issue_id_fkey FOREIGN KEY (source_issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_relations
    ADD CONSTRAINT issue_relations_target_issue_id_fkey FOREIGN KEY (target_issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_template_assignees
    ADD CONSTRAINT issue_template_assignees_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.issue_templates(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_template_assignees
    ADD CONSTRAINT issue_template_assignees_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_template_items
    ADD CONSTRAINT issue_template_items_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.issue_templates(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_template_labels
    ADD CONSTRAINT issue_template_labels_label_id_fkey FOREIGN KEY (label_id) REFERENCES public.labels(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_template_labels
    ADD CONSTRAINT issue_template_labels_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.issue_templates(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_templates
    ADD CONSTRAINT issue_templates_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_templates
    ADD CONSTRAINT issue_templates_state_id_fkey FOREIGN KEY (state_id) REFERENCES public.states(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issues
    ADD CONSTRAINT issues_closed_by_id_fkey FOREIGN KEY (closed_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issues
    ADD CONSTRAINT issues_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issues
    ADD CONSTRAINT issues_description_updated_by_id_fkey FOREIGN KEY (description_updated_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issues
    ADD CONSTRAINT issues_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.issues(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issues
    ADD CONSTRAINT issues_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issues
    ADD CONSTRAINT issues_state_id_fkey FOREIGN KEY (state_id) REFERENCES public.states(id);

ALTER TABLE ONLY public.labels
    ADD CONSTRAINT labels_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.module_issues
    ADD CONSTRAINT module_issues_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.module_issues
    ADD CONSTRAINT module_issues_module_id_fkey FOREIGN KEY (module_id) REFERENCES public.modules(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.modules
    ADD CONSTRAINT modules_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_comment_id_fkey FOREIGN KEY (comment_id) REFERENCES public.comments(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_cycle_id_fkey FOREIGN KEY (cycle_id) REFERENCES public.cycles(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_recipient_id_fkey FOREIGN KEY (recipient_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.project_members
    ADD CONSTRAINT project_members_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.project_members
    ADD CONSTRAINT project_members_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.project_repositories
    ADD CONSTRAINT project_repositories_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.project_repositories
    ADD CONSTRAINT project_repositories_repository_id_fkey FOREIGN KEY (repository_id) REFERENCES public.github_repositories(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.retro_note_votes
    ADD CONSTRAINT retro_note_votes_note_id_fkey FOREIGN KEY (note_id) REFERENCES public.retro_notes(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.retro_note_votes
    ADD CONSTRAINT retro_note_votes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.retro_notes
    ADD CONSTRAINT retro_notes_author_id_fkey FOREIGN KEY (author_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.retro_notes
    ADD CONSTRAINT retro_notes_cycle_id_fkey FOREIGN KEY (cycle_id) REFERENCES public.cycles(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.retro_notes
    ADD CONSTRAINT retro_notes_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.retro_notes
    ADD CONSTRAINT retro_notes_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.saved_views
    ADD CONSTRAINT saved_views_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.saved_views
    ADD CONSTRAINT saved_views_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.states
    ADD CONSTRAINT states_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.stored_files
    ADD CONSTRAINT stored_files_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES public.issues(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.stored_files
    ADD CONSTRAINT stored_files_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.stored_files
    ADD CONSTRAINT stored_files_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.stored_files
    ADD CONSTRAINT stored_files_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.verification_tokens
    ADD CONSTRAINT verification_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_invitations
    ADD CONSTRAINT workspace_invitations_custom_role_id_fkey FOREIGN KEY (custom_role_id) REFERENCES public.workspace_roles(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.workspace_invitations
    ADD CONSTRAINT workspace_invitations_invited_by_id_fkey FOREIGN KEY (invited_by_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.workspace_invitations
    ADD CONSTRAINT workspace_invitations_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_members
    ADD CONSTRAINT workspace_members_custom_role_id_fkey FOREIGN KEY (custom_role_id) REFERENCES public.workspace_roles(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.workspace_members
    ADD CONSTRAINT workspace_members_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_members
    ADD CONSTRAINT workspace_members_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_roles
    ADD CONSTRAINT workspace_roles_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.users(id);
