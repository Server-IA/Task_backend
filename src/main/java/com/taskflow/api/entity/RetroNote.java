package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.RetroColumn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Nota de la retrospectiva de un ciclo. Cada usuario vota una vez por nota (y tiene un máximo de votos por
 * retrospectiva); una acción ({@code ACTION}) puede convertirse en tarea, que queda en {@code issue}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "retro_notes")
public class RetroNote extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private Cycle cycle;

    /** En la base de datos se llama {@code retro_column} porque {@code COLUMN} es palabra reservada. */
    @Enumerated(EnumType.STRING)
    @Column(name = "retro_column", nullable = false, length = 10)
    private RetroColumn column;

    @Column(nullable = false, length = 500)
    private String text;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    /** Tarea creada a partir de esta acción; {@code null} si no se convirtió (o si la tarea se borró). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id")
    private Issue issue;

    @ManyToMany
    @JoinTable(name = "retro_note_votes",
            joinColumns = @JoinColumn(name = "note_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> voters = new HashSet<>();

    public RetroNote(Cycle cycle, RetroColumn column, String text, User author) {
        this.cycle = cycle;
        this.column = column;
        this.text = text;
        this.author = author;
    }

    public boolean isVotedBy(UUID userId) {
        return voters.stream().anyMatch(voter -> voter.getId().equals(userId));
    }
}
