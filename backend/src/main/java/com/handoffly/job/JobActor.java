package com.handoffly.job;

import com.handoffly.common.domain.ActorType;
import com.handoffly.user.User;

/**
 * Who did something to a job: a stable id (never the name, which can change), the name they had at that moment, and what kind of actor
 * they are. Always built on the server from the authenticated account, never from anything the client sends. Today only the customer
 * themselves can run or change their jobs; another kind of actor is another {@link ActorType} here, not a change to the history.
 */
public record JobActor(ActorType type, Long id, String name) {

    /** The signed-in customer. */
    public static JobActor customer(User user) {
        return new JobActor(ActorType.USER, user.getId(), user.getDisplayName());
    }
}
