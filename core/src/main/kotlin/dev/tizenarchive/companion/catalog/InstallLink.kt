package dev.tizenarchive.companion.catalog

/** `tizen-archive://install?package=<id>[&version=<v>]`, the link behind the website's install button. */
public data class InstallLink(val packageId: String, val version: String?)

public class InvalidLinkException(message: String) : Exception(message)

/**
 * The link comes from an arbitrary web page, so it may carry nothing but identifiers: the catalog server is
 * never taken from it (see [CatalogClient]), and the app must ask the user before doing anything.
 */
public object InstallLinkParser {
    private const val SCHEME = "tizen-archive"
    private const val MAX_IDENTIFIER = 128

    public fun parse(link: String): InstallLink {
        val invalid = { InvalidLinkException("Unsupported link") }
        val (scheme, rest) = link.split("://", limit = 2).takeIf { it.size == 2 } ?: throw invalid()
        if (!scheme.equals(SCHEME, ignoreCase = true)) throw invalid()
        val (target, query) = rest.split('?', limit = 2).takeIf { it.size == 2 } ?: throw invalid()
        if (target.trimEnd('/') != "install") throw invalid()

        var packageId: String? = null
        var version: String? = null
        for (pair in query.substringBefore('#').split('&')) {
            val (key, value) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: throw invalid()
            when {
                key == "package" && packageId == null -> packageId = value
                key == "version" && version == null -> version = value
                else -> throw invalid()
            }
        }
        val id = packageId ?: throw invalid()
        if (!isIdentifier(id) || (version != null && !isIdentifier(version))) {
            throw InvalidLinkException("Invalid identifier in the link")
        }
        return InstallLink(id, version)
    }

    /** Package ids and versions end up in URLs and file names, so only a narrow alphabet is accepted. */
    public fun isIdentifier(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_IDENTIFIER &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' || it == '+' }
}
