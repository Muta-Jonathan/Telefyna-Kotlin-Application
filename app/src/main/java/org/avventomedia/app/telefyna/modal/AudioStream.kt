package org.avventomedia.app.telefyna.modal

/**
 * Radio stream of the programme audio to an Icecast or Shoutcast server (AAC, 44.1 kHz stereo).
 * [port] is the server's listener port; Shoutcast sources connect to [port] + 1 (DNAS v1 / v2 legacy source).
 */
data class AudioStream(
    var enabled: Boolean = false,
    var server: Server = Server.ICECAST,
    var host: String? = null,
    var port: Int = 8000,
    // Icecast only: the mount point listeners tune to, e.g. /live
    var mount: String = "/live",
    // Icecast only: source user
    var user: String = "source",
    var password: String? = null,
    var bitrateKbps: Int = 128,
    // Station name shown to listeners; defaults to the config's name
    var name: String? = null,
    var genre: String? = null,
    var description: String? = null,
    var url: String? = null,
    // List the stream in the server's public directory
    var publicListing: Boolean = false,
    // Send the now playing programme as the stream title
    var metadata: Boolean = true
) {
    enum class Server { ICECAST, SHOUTCAST }

    val isUsable: Boolean get() = enabled && !host.isNullOrBlank() && !password.isNullOrBlank()

    /** Where the server is, for logs (never the password). */
    fun label(): String = "${server.name.lowercase()}://$host:$port${if (server == Server.ICECAST) mount else ""}"
}
