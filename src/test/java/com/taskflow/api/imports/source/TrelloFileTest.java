package com.taskflow.api.imports.source;

import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El archivo JSON que exporta un tablero de Trello: el mismo tablero que da la API, con sus acciones (de todo tipo, no
 * solo comentarios) y los adjuntos nombrados dentro de cada tarjeta.
 */
class TrelloFileTest {

    /** Los ocho primeros dígitos son la fecha en que se creó: 1 y 2 de septiembre de 2026 a las 08:00 UTC. */
    private static final String CARD_A = "6a968600aaaaaaaaaaaaaaaa";
    private static final String CARD_B = "6a97d780bbbbbbbbbbbbbbbb";

    private static final String BOARD = """
            {"id":"5abbe4b7ddc1b351ef961414","name":"Portal de clientes","desc":"","closed":false,
             "url":"https://trello.com/b/dQHqCohZ/portal-de-clientes","shortUrl":"https://trello.com/b/dQHqCohZ",
             "labelNames":{"green":"","red":"Urgente"},"prefs":{"permissionLevel":"private"},
             "lists":[{"id":"l-por-hacer","name":"Por hacer","closed":false,"pos":16384},
                      {"id":"l-revision","name":"En revisión","closed":false,"pos":32768},
                      {"id":"l-hecho","name":"Hecho","closed":false,"pos":49152}],
             "labels":[{"id":"e-urgente","name":"Urgente","color":"red"}],
             "members":[{"id":"m-ana","fullName":"Ana Torres","username":"anatorres"},
                        {"id":"m-luis","fullName":"","username":"luismunoz"}],
             "memberships":[{"id":"x","idMember":"m-ana","memberType":"admin"}],
             "cards":[
               {"id":"%2$s","idShort":8,"name":"Exportar a CSV","desc":"","closed":true,"idList":"l-hecho","idMembers":["m-luis"],
                "idLabels":[],"labels":[],"due":"2026-10-15T22:00:00.000Z","dueComplete":true,"start":null,
                "dateLastActivity":"2026-10-14T09:00:00.000Z","idChecklists":[],"shortLink":"bbbbbbbb",
                "shortUrl":"https://trello.com/c/bbbbbbbb","pos":16384,"badges":{"comments":40},"attachments":[]},
               {"id":"%1$s","idShort":7,"name":"Ver el historial de pedidos","desc":"Como cliente quiero ver **mis pedidos**",
                "closed":false,"idList":"l-revision","idMembers":["m-ana"],"idLabels":["e-urgente"],
                "labels":[{"id":"e-urgente","name":"Urgente","color":"red"}],"due":null,"dueComplete":false,"start":"2026-09-05T00:00:00.000Z",
                "dateLastActivity":"2026-09-20T09:00:00.000Z","idChecklists":["k-pasos"],"shortLink":"aaaaaaaa",
                "shortUrl":"https://trello.com/c/aaaaaaaa","pos":16384,"badges":{"comments":2},
                "attachments":[{"id":"a-mockup","name":"mockup.png","bytes":2048,"mimeType":"image/png","isUpload":true,
                                "url":"https://trello.com/1/cards/%1$s/attachments/a-mockup/download/mockup.png"}]}],
             "checklists":[{"id":"k-pasos","name":"Pasos","idCard":"%1$s","pos":16384,"checkItems":[
               {"id":"i-1","name":"Armar la consulta","state":"complete","pos":17000},
               {"id":"i-2","name":"Probar el filtro","state":"incomplete","pos":34000}]}],
             "actions":[
               {"id":"c-2","idMemberCreator":"m-luis","type":"commentCard","date":"2026-09-12T10:00:00.000Z",
                "data":{"text":"Ya quedó","card":{"id":"%1$s","idShort":7,"shortLink":"aaaaaaaa"},"board":{"id":"5abbe4b7ddc1b351ef961414"}},
                "memberCreator":{"id":"m-luis","fullName":"","username":"luismunoz"}},
               {"id":"u-1","idMemberCreator":"m-ana","type":"updateCard","date":"2026-09-11T10:00:00.000Z",
                "data":{"card":{"id":"%1$s","idList":"l-revision"},"old":{"idList":"l-por-hacer"}}},
               {"id":"c-1","idMemberCreator":"m-ana","type":"commentCard","date":"2026-09-10T10:00:00.000Z",
                "data":{"text":"Revisa el paginado","card":{"id":"%1$s","idShort":7,"shortLink":"aaaaaaaa"}},
                "memberCreator":{"id":"m-ana","fullName":"Ana Torres","username":"anatorres"}},
               {"id":"n-1","idMemberCreator":"m-ana","type":"createCard","date":"2026-09-01T08:00:00.000Z",
                "data":{"card":{"id":"%1$s"},"list":{"id":"l-por-hacer","name":"Por hacer"}}}],
             "customFields":[],"pluginData":[]}
            """.formatted(CARD_A, CARD_B);

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void leeElTableroConSusListasTarjetasPersonasYLosComentariosQueTrae() {
        ImportBundle bundle = TrelloFile.toBundle(bytes(BOARD));

        ExtProject project = bundle.projects().get(0);
        assertThat(bundle.projects()).hasSize(1);
        assertThat(project.id()).isEqualTo("5abbe4b7ddc1b351ef961414");
        assertThat(project.key()).isNull();
        assertThat(project.name()).isEqualTo("Portal de clientes");
        assertThat(project.description()).isNull();
        assertThat(project.url()).isEqualTo("https://trello.com/b/dQHqCohZ/portal-de-clientes");
        // Una lista abierta es un estado aunque no tenga tarjetas
        assertThat(project.statuses()).extracting(status -> status.name() + " " + status.category())
                .containsExactly("Por hacer TODO", "En revisión IN_PROGRESS", "Hecho DONE");

        ExtIssue card = project.issues().get(0);
        assertThat(card.id()).isEqualTo(CARD_A);
        assertThat(card.number()).isEqualTo(7);
        assertThat(card.kind()).isEqualTo(Kind.TASK);
        assertThat(card.type()).isNull();
        assertThat(card.title()).isEqualTo("Ver el historial de pedidos");
        assertThat(card.description()).isEqualTo("Como cliente quiero ver **mis pedidos**");
        assertThat(card.status()).isEqualTo("En revisión");
        assertThat(card.statusCategory()).isEqualTo(StatusCategory.IN_PROGRESS);
        assertThat(card.labels()).containsExactly("Urgente");
        assertThat(card.assigneeIds()).containsExactly("m-ana");
        assertThat(card.startDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(card.dueDate()).isNull();
        assertThat(card.completedAt()).isNull();
        // El archivo no trae cuándo se creó cada tarjeta: sale de su identificador
        assertThat(card.createdAt()).isEqualTo(Instant.parse("2026-09-01T08:00:00Z"));
        assertThat(card.url()).isEqualTo("https://trello.com/c/aaaaaaaa");
        assertThat(card.checklist()).extracting(item -> item.text() + " " + item.done())
                .containsExactly("Armar la consulta true", "Probar el filtro false");
        // Los comentarios, del más viejo al más nuevo; mover o crear la tarjeta no es comentar
        assertThat(card.comments()).extracting(comment -> comment.id() + " " + comment.authorId() + " " + comment.body())
                .containsExactly("c-1 m-ana Revisa el paginado", "c-2 m-luis Ya quedó");
        assertThat(card.commentCount()).isEqualTo(2);
        // El archivo nombra los adjuntos pero no da con qué descargarlos
        assertThat(card.attachments()).isEmpty();

        ExtIssue exported = project.issues().get(1);
        assertThat(exported.archived()).isTrue();
        assertThat(exported.statusCategory()).isEqualTo(StatusCategory.DONE);
        assertThat(exported.dueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(exported.completedAt()).isEqualTo(Instant.parse("2026-10-15T22:00:00Z"));
        // El archivo solo trae las últimas 1000 acciones: no se prometen comentarios que no están
        assertThat(exported.comments()).isEmpty();
        assertThat(exported.commentCount()).isZero();

        // Sin nombre completo, la persona se conoce por su usuario
        assertThat(bundle.users()).extracting(user -> user.id() + " " + user.name() + " " + user.email())
                .containsExactly("m-ana Ana Torres null", "m-luis luismunoz null");
    }

    @Test
    void unArchivoQueNoEsUnTableroSeRechaza() {
        List<String> files = List.of("", "esto no es JSON", "[]", "\"tablero\"", "{}", "{\"name\":\"Tablero\",\"cards\":[]}",
                "{\"name\":\"Tablero\",\"lists\":{},\"cards\":[]}",
                // La tarjeta sola, que Trello también exporta
                "{\"id\":\"" + CARD_A + "\",\"name\":\"Ver el historial de pedidos\",\"idList\":\"l-revision\",\"checklists\":[]}",
                // Lo que exporta otra herramienta
                "{\"issues\":[{\"id\":\"10001\",\"key\":\"PORT-1\"}]}");

        for (String file : files) {
            assertThatThrownBy(() -> TrelloFile.toBundle(bytes(file))).as(file)
                    .isInstanceOf(SourceException.class).hasMessage(TrelloFile.NOT_A_BOARD);
        }
    }

    @Test
    void unTableroVacioEsUnProyectoSinTareas() {
        ImportBundle bundle = TrelloFile.toBundle(bytes("{\"name\":\"Tablero nuevo\",\"lists\":[],\"cards\":[]}"));

        ExtProject project = bundle.projects().get(0);
        assertThat(project.name()).isEqualTo("Tablero nuevo");
        assertThat(project.statuses()).isEmpty();
        assertThat(project.issues()).isEmpty();
        assertThat(bundle.users()).isEmpty();
    }

    @Test
    void loQueFaltaOVieneNuloNoRompeLaLectura() {
        ImportBundle bundle = TrelloFile.toBundle(bytes("""
                {"id":"5abbe4b7ddc1b351ef961414","name":"Tablero","desc":null,"url":null,"shortUrl":"https://trello.com/b/dQHqCohZ",
                 "lists":[{"id":"l-1","name":"Pendientes","closed":false}],
                 "members":null,"labels":null,"checklists":null,"actions":null,
                 "cards":[
                   {"id":"corto","name":"Sin casi nada","idList":"l-otra"},
                   {"id":"%s","idShort":null,"name":"Con nulos","desc":null,"closed":null,"idList":"l-1","idMembers":null,"idLabels":null,
                    "labels":null,"due":"mañana","dueComplete":true,"start":null,"dateLastActivity":"2026-10-14T09:00:00.000Z",
                    "shortUrl":null,"url":"https://trello.com/c/aaaaaaaa/7-con-nulos","badges":null,"attachments":null}]}
                """.formatted(CARD_A)));

        ExtProject project = bundle.projects().get(0);
        assertThat(project.url()).isEqualTo("https://trello.com/b/dQHqCohZ");
        // La lista que no viene en el archivo no hace perder la tarjeta
        assertThat(project.statuses()).extracting(status -> status.name() + " " + status.category())
                .containsExactly("Pendientes BACKLOG", "Unknown TODO");

        ExtIssue complete = project.issues().get(0);
        assertThat(complete.title()).isEqualTo("Con nulos");
        assertThat(complete.number()).isNull();
        assertThat(complete.description()).isEmpty();
        assertThat(complete.archived()).isFalse();
        assertThat(complete.assigneeIds()).isEmpty();
        assertThat(complete.labels()).isEmpty();
        assertThat(complete.dueDate()).isNull();
        // Cumplida y sin una fecha que se entienda: queda la de su último movimiento
        assertThat(complete.completedAt()).isEqualTo(Instant.parse("2026-10-14T09:00:00Z"));
        assertThat(complete.url()).isEqualTo("https://trello.com/c/aaaaaaaa/7-con-nulos");

        ExtIssue bare = project.issues().get(1);
        assertThat(bare.status()).isEqualTo("Unknown");
        assertThat(bare.createdAt()).isNull();
        assertThat(bare.url()).isNull();
        assertThat(bare.checklist()).isEmpty();
        assertThat(bare.comments()).isEmpty();
    }
}
