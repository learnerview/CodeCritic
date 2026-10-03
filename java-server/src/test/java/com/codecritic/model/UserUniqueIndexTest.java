package com.codecritic.model;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code users.username} must be unique <em>in Mongo</em>, not merely checked in Java.
 *
 * <p>Registration and the default-user bootstrap are both check-then-act, so a unique index
 * is the only thing that makes the check authoritative. Without it, two concurrent
 * registrations of the same username both insert and {@code findByUsername} then returns an
 * arbitrary one of the duplicates -- which is how a login can be checked against a stale
 * password hash and succeed or fail at random.
 *
 * <p>This asserts the declared schema intent only. Spring Boot 3 does not create indexes on
 * startup unless {@code spring.data.mongodb.auto-index-creation=true}, which this project
 * does not set, so the index still has to be created on the deployed database.
 */
class UserUniqueIndexTest {

    @Test
    void usernameIsDeclaredUnique() throws NoSuchFieldException {
        Field username = User.class.getDeclaredField("username");

        Indexed indexed = username.getAnnotation(Indexed.class);

        assertThat(indexed)
                .as("users.username must carry @Indexed(unique = true)")
                .isNotNull();
        assertThat(indexed.unique())
                .as("a non-unique index would not prevent duplicate usernames")
                .isTrue();
    }

    @Test
    void usernameIsNotMappedToACollectionScopedFieldName() throws NoSuchFieldException {
        Field username = User.class.getDeclaredField("username");

        Indexed indexed = username.getAnnotation(Indexed.class);

        // An explicit empty name keeps the field name as the index key; a stale value here
        // would build a differently-named index than the one the constraint relies on.
        assertThat(indexed.name()).isEmpty();
    }
}