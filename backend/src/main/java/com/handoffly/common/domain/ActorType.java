package com.handoffly.common.domain;

/** Who performed an action — an app user, an unauthenticated recipient, or the system. */
public enum ActorType {
    USER,
    RECIPIENT,
    SYSTEM
}
