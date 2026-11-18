package io.github.hypercar.settings

import io.github.hypercar.net.PhoneIdentitySnapshot

/** A user edit, including clearing the field, ends all pending automatic writes. */
internal class SavedPhoneMac(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
) {
    private var revision = 0L
    data class Selection(val address: String?, val revision: Long)

    @Synchronized fun ticket(): Long = revision
    @Synchronized fun selection(): Selection = Selection(read(), revision)

    @Synchronized fun edit(value: String?) {
        revision++
        write(PhoneIdentitySnapshot.normalize(value))
    }

    @Synchronized fun remember(ticket: Long, address: String?): Boolean {
        val valid = PhoneIdentitySnapshot.normalize(address) ?: return false
        if (revision != ticket || read() != null) return false
        write(valid)
        return true
    }
}
