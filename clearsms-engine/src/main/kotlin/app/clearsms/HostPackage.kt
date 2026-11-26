package app.clearsms

import android.content.Context

/**
 * Host application id for FileProvider authorities and similar identifiers.
 *
 * Clear SMS used [BuildConfig.APPLICATION_ID] when it was a standalone app.
 * This engine is compiled as a library inside Call Guardian, so the authority
 * must be the host package (`io.github.repr0bated.callguardian[.debug]`), not
 * the library namespace `app.clearsms`. Call [init] from the host Application.
 */
object HostPackage {
    @Volatile
    var applicationId: String = "app.clearsms"
        private set

    val fileProviderAuthority: String get() = "$applicationId.fileprovider"

    fun init(context: Context) {
        applicationId = context.packageName
    }
}
