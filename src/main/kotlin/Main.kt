import io.jooby.*
import io.jooby.runApp
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlinx.cli.*
import org.brotli.dec.BrotliInputStream
import java.util.*

// Simple logging: print to STDOUT
fun log(message: String) = println(message)

// Ensure there's no trailing `/`
fun getCleanUrl(url: String): String = url.trimEnd('/')

fun getUrlMap(sourcePort: Int, destinations: List<String>): Map<String, String> {
    /* Return a mapping of paths to destinations (with a default empty path).

    For destination strings, expected format is one of three:
    A) URL only: https://example.com
    B) URL + path (inclusive): api=https://example.com
        This will result in locahost/api -> https://example.com/api
    C) URL + path (exclusive): api^https://example.com
        This will result in locahost/api -> https://example.com/
     */
    var urlMap = mutableMapOf<String, String>()
    val source = "http://localhost:$sourcePort"
    destinations.forEach {
        when {
            it.contains("^") -> {
                val pair = it.split('^')
                val path = pair[0]
                val dest = getCleanUrl(pair[1])
                urlMap[path] = dest
            }
            it.contains("=") -> {
                val pair = it.split('=')
                val path = pair[0]
                val dest = getCleanUrl(pair[1])
                urlMap[path] = dest
            }
            else -> {
                if (urlMap.containsKey("")) {
                    log("Error: More than one destination without a path mapping found.")
                    log("There can only be one default.")
                    log("Example: -d http://localhost:3000 -d api=http:localhost:5000")
                    kotlin.system.exitProcess(1)
                }
                urlMap[""] = getCleanUrl(it)
            }
        }
    }
    if (!urlMap.containsKey("")) {
        log("Error: No default destination found. Add a '-d' destination without a root path.")
        log("Example: -d http://localhost:3000")
        kotlin.system.exitProcess(1)
    }
    return urlMap
}

fun getHeadersArray(ctx: Context): Array<String> {
    /* Get an array of headers as a typed array - removing "restricted" headers. */
    val restrictedHeaders = listOf("Connection", "Content-Length", "Host", "Upgrade")
    var headerMap = ctx.headerMap()
    restrictedHeaders.forEach { headerMap.remove(it) }
    return headerMap.flatMap { (key, values) -> listOf(key).plus(values) }.toTypedArray()
}

fun getUri(urlMap: Map<String, String>, ctx: Context): URI {
    /* Get a URI off the current request, using the url mapping provided.
    This is where the routing happens.
     */
    val pathElements = ctx.requestPath.substringAfter('/').split("/")
    val rootPath = pathElements.first()
    if (urlMap.keys.contains(rootPath)) {
        val destinationRoot = urlMap[rootPath]
        val path = ctx.requestPath.substringAfter("/$rootPath")
        val destinationURL =  "${destinationRoot}${path}${ctx.query.queryString()}"
        log("${ctx.method} ${ctx.requestURL} -> $destinationURL")
        return URI(destinationURL)
    }
    val destinationRoot = urlMap[""]
    val destinationURL = "${destinationRoot}${ctx.requestPath}${ctx.query.queryString()}"
    log("${ctx.method} ${ctx.requestURL} -> $destinationURL")
    return URI(destinationURL)
}

fun getMediaType(contentType: Optional<String>): MediaType {
    /* Mapping of incoming contentType to Jooby MediaType
     */
    if (contentType.isEmpty) { return MediaType.valueOf(MediaType.ALL) }
    val typeMap = mapOf(
        "application/javascript; charset=UTF-8" to MediaType.JS,
        "text/css; charset=UTF-8" to MediaType.CSS,
        "text/html; charset=UTF-8" to MediaType.HTML,
    )
    val target = typeMap[contentType]
    if (target != null) {
        return MediaType.valueOf(target)
    }
    return MediaType.valueOf(contentType.get())
}

fun getResponse(httpClient: HttpClient, request: HttpRequest, ctx: Context): Any {
    /* Given a request, build a response to filter through Jooby. */
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    ctx.responseCode = StatusCode.valueOf(response.statusCode())
    ctx.responseType = getMediaType(response.headers().firstValue("content-type"))
    val gzipEncoding = response.headers().allValues("content-encoding").contains("gzip")
    val brEncoding = response.headers().allValues("content-encoding").contains("br")
    return when {
        gzipEncoding -> {
            try {
                GZIPInputStream(response.body()).bufferedReader(UTF_8).use { it.readText() }
            } catch (e: ZipException) {
                log(e.message!!)
            }
        }
        brEncoding -> {
            try {
                BrotliInputStream(response.body()).bufferedReader(UTF_8).use { it.readText() }
            } catch (e: ZipException) {
                log(e.message!!)
            }
        }
        else -> {
            response.body()
        }
    }
}

fun handleRequest(
    urlMap: Map<String, String>, httpClient: HttpClient,
    ctx: Context, method: String, payload: Boolean = false): Any {
    /* Handle an incoming request, and return a processed response. */
    val headers = getHeadersArray(ctx)
    val uri = getUri(urlMap, ctx)
    val body = if (payload) {
        HttpRequest.BodyPublishers.ofString(ctx.body.toString())
    } else { HttpRequest.BodyPublishers.noBody() }
    var rbuilder = HttpRequest.newBuilder()
        .uri(uri)
        .method(method, body)
    if (headers.isNotEmpty()) {
        rbuilder = rbuilder.headers(*headers)
    }
    val request = rbuilder.build()
    return getResponse(httpClient, request, ctx)
}

fun main(args: Array<String>) {
    runApp(args) {
        val parser = ArgParser("locurlfwd")
        val sourcePort by parser.argument(ArgType.Int, description = "Source Port")
        val destination by parser.option(ArgType.String, shortName = "d", description = "Destination").multiple()
        parser.parse(args)
        val urlMap = getUrlMap(sourcePort, destination)
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
        serverOptions {
            port = sourcePort
            bufferSize = 16384
            ioThreads = 16
            workerThreads = 64
            maxRequestSize = 10485760
        }
        delete ("*") {
            handleRequest(urlMap, httpClient, ctx, "DELETE")
        }
        get ("*") {
            handleRequest(urlMap, httpClient, ctx, "GET")
        }
        head ( "*") {
            handleRequest(urlMap, httpClient, ctx, "HEAD")
        }
        options ( "*") {
            handleRequest(urlMap, httpClient, ctx, "OPTIONS")
        }
        patch ("*") {
            handleRequest(urlMap, httpClient, ctx, "PATCH",true)
        }
        post ("*") {
            handleRequest(urlMap, httpClient, ctx, "POST",true)
        }
        put ("*") {
            handleRequest(urlMap, httpClient, ctx, "PUT",true)
        }
    }
}
