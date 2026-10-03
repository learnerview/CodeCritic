package com.codecritic.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {

    @Id
    private String id;

    /**
     * Unique at the database level, not merely in application code. Registration is a
     * check-then-act ({@code findByUsername} then {@code save}), which two concurrent
     * requests can both pass; without this index they both insert, {@code findByUsername}
     * then returns an arbitrary one of the duplicates, and a user can be authenticated
     * against a stale password hash.
     *
     * <p>NOTE: {@code @Indexed} only declares the intent. Spring Boot 3 does not create
     * indexes on startup unless {@code spring.data.mongodb.auto-index-creation=true} (unset
     * in this project, so it defaults to {@code false}), so this index must be created
     * explicitly -- see the deploy notes.
     */
    @Indexed(unique = true)
    private String username;

    private String password;

    private String role;

    private Instant createdAt;
}