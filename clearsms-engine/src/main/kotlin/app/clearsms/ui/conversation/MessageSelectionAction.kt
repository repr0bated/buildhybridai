package app.clearsms.ui.conversation

/**
 * Subset of Clear SMS conversation multi-select actions, shipped here so the
 * engine can compile without the Compose conversation screens.
 */
enum class MessageSelectionAction {
    COPY,
    DELETE,
    MORE_DETAILS,
    FORWARD,
    SHARE,
    SELECT_ALL,
    COPY_OTP,
    ADD_RULE,
    SAVE_ATTACHMENT,
}
