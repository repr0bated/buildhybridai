package app.clearsms

/**
 * Optional host callback after an incoming MMS has been downloaded and
 * stored. Call Guardian uses this to run its screening pipeline and post a
 * per-conversation notification. SMS ingest is owned by the host receiver
 * and does not go through this hook.
 */
fun interface IncomingMmsListener {
    fun onDownloaded(sender: String, body: String, timestampMs: Long, recipients: List<String>)
}

object HostIncoming {
    @Volatile
    var mmsListener: IncomingMmsListener? = null
}
