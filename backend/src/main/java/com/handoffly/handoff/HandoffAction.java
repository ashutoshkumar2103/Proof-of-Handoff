package com.handoffly.handoff;

/** Actions the owner may take on a handoff in its current state (drives the UI). */
public enum HandoffAction {
    EDIT,
    DELETE,
    SUBMIT,
    RESEND_LINK,
    CANCEL,
    RECORD_RETURN,
    CONFIRM_RETURN,
    REQUEST_MISSING_CONFIRMATION,
    DISPUTE,
    CLOSE,
    ADD_ATTACHMENT
}
