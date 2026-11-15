-- Personas invitadas desde una importación: al aceptar la invitación entran a los proyectos de esa importación, tengan
-- o no tareas asignadas. Quedan apuntadas por correo; deshacer la importación borra las de esa importación.
CREATE TABLE public.import_pending_members (
    id bigserial PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES public.import_jobs(id) ON DELETE CASCADE,
    workspace_id uuid NOT NULL REFERENCES public.workspaces(id) ON DELETE CASCADE,
    email character varying(255) NOT NULL,
    project_id uuid NOT NULL REFERENCES public.projects(id) ON DELETE CASCADE,
    CONSTRAINT uk_import_pending_members UNIQUE (project_id, email)
);

CREATE INDEX idx_import_pending_members_email ON public.import_pending_members USING btree (workspace_id, email);
CREATE INDEX idx_import_pending_members_job ON public.import_pending_members USING btree (job_id);
