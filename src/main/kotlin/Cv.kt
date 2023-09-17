package co.codecv

import com.hexagonkt.args.*
import com.hexagonkt.args.Property.Companion.HELP
import com.hexagonkt.args.Property.Companion.VERSION
import com.hexagonkt.core.*
import com.hexagonkt.core.logging.LoggingManager
import com.hexagonkt.core.logging.logger
import com.hexagonkt.core.media.mediaTypeOfOrNull
import com.hexagonkt.helpers.CodedException
import com.hexagonkt.helpers.properties
import com.hexagonkt.helpers.wordsToCamel
import com.hexagonkt.http.handlers.HttpContext
import com.hexagonkt.http.model.ContentType
import com.hexagonkt.http.model.Header
import com.hexagonkt.http.server.HttpServer
import com.hexagonkt.http.server.HttpServerSettings
import com.hexagonkt.http.server.callbacks.UrlCallback
import com.hexagonkt.http.server.netty.NettyServerAdapter
import com.hexagonkt.http.server.serve
import com.hexagonkt.logging.jul.JulLoggingAdapter
import com.hexagonkt.serialization.SerializationManager
import com.hexagonkt.serialization.jackson.json.Json
import com.hexagonkt.serialization.jackson.toml.Toml
import com.hexagonkt.serialization.jackson.yaml.Yaml
import com.hexagonkt.serialization.parseMap
import com.hexagonkt.serialization.serialize
import com.hexagonkt.templates.TemplateManager
import com.hexagonkt.templates.pebble.PebbleAdapter
import com.hexagonkt.web.callContext
import com.hexagonkt.web.template
import io.vertx.core.json.JsonObject
import io.vertx.json.schema.Draft.DRAFT7
import io.vertx.json.schema.JsonSchema
import io.vertx.json.schema.JsonSchemaOptions
import io.vertx.json.schema.Validator
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.system.exitProcess


const val preventExitFlag: String = "PREVENT_EXIT"
const val exitCodeProperty: String = "EXIT_CODE"

const val spec: String = "classpath:spec.yml"
const val schema: String = "classpath:cv.schema.json"
const val defaultTemplate: String = "classpath:examples/cv.html"
const val buildProperties: String = "classpath:META-INF/build.properties"
const val mainPage: String = "classpath:ui.html"

const val serveCommandName: String = "serve"
const val createCommandName: String = "create"
const val validateCommandName: String = "validate"

const val urlParamName: String = "url"
const val fileParamName: String = "file"
const val templateOptShortName: Char = 't'
const val formatOptShortName: Char = 'f'

lateinit var server: HttpServer

fun main(vararg args: String) {
    try {
        val buildProperties = properties(URL(buildProperties))
        val project = buildProperties.require("project")

        LoggingManager.adapter = JulLoggingAdapter(messageOnly = true, stream = System.err)
        LoggingManager.defaultLoggerName = project
        SerializationManager.formats = linkedSetOf(Yaml, Json, Toml)

        val program = createProgram(buildProperties)
        val command = program.parse(args)

        when (command.name) {
            serveCommandName, project -> serve(command)
            createCommandName -> create(command)
            validateCommandName -> validate(command)
        }
    } catch (e: Exception) {
        exit(e)
    }
}

private fun exit(exception: Exception) {
    logger.error(exception) { exception.message }
    val code = (exception as? CodedException)?.code ?: 500

    if (Jvm.systemFlag(preventExitFlag))
        System.setProperty(exitCodeProperty, code.toString())
    else
        exitProcess(code)
}

private fun createProgram(buildProperties: Map<String, String>): Program {
    val urlParamDescription = "URL for the CV file to use. If no schema, 'file' is assumed"
    val browseFlag = Flag('b', "browse", "Open browser with served CV")
    val urlParam = Parameter<String>(urlParamName, urlParamDescription, optional = false)

    val serveCommand = Command(
        name = serveCommandName,
        title = "Serve a CV document",
        description = "Serve the CV document supplied, allowing it to be rendered on a browser",
        properties = setOf(HELP, browseFlag, urlParam),
    )

    val createCommand = Command(
        name = createCommandName,
        title = "Create a CV document",
        description = "Creates a new CV document based on a template",
        properties = setOf(
            HELP,
            Option<String>(
                shortName = templateOptShortName,
                name = "template",
                description = "Template used to create the new CV",
                regex = Regex("(regular|full|minimum)"),
                value = "regular",
            ),
            Option<String>(
                shortName = formatOptShortName,
                name = "format",
                description = "Data format used to store the generated document",
                regex = Regex("(yaml|toml|json)"),
                value = "yaml",
            ),
            Parameter<String>(
                name = fileParamName,
                description = "File to store the CV document. Document printed on stdout if missed",
            )
        ),
    )

    val validateCommand = Command(
        name = validateCommandName,
        title = "Validate an existing CV",
        description = "Returns a list of errors and a 400 code if the CV document is not valid",
        properties = setOf(HELP, urlParam),
    )

    return Program(
        name = buildProperties.require("project"),
        version = buildProperties.require("version"),
        description = buildProperties.require("description"),
        properties = setOf(VERSION) + serveCommand.properties,
        commands = setOf(serveCommand, createCommand, validateCommand),
    )
}

private fun create(command: Command) {
    val template = command.propertyValueOrNull<String>(templateOptShortName.toString())
    val format = command.propertyValueOrNull<String>(formatOptShortName.toString())
    val extension = if (format == "yaml") "yml" else format
    val file = command.propertyValueOrNull<String>(fileParamName)

    val url = URL("classpath:examples/$template.cv.$extension")
    val content = url.readText()

    if (file != null)
        File(file).writeText(content)
    else
        logger.info { content }
}

private fun urlParameter(command: Command): URL {
    val urlParameter = command.propertyValue<String>(urlParamName)
        .let { if (URI(it).scheme != null) it else "file:$it" }

    val url = URL(urlParameter)
    return if (!url.exists()) throw CodedException(404, "CV url not found: $url") else url
}

private fun validate(command: Command) {
    val url = urlParameter(command)

    if (!url.exists())
        throw CodedException(404, "CV url not found: $url")

    val valid = validate(url.parseMap())

    if (!valid)
        throw CodedException(400, "Document in '$url' don't comply with CV schema")
}

private fun serve(command: Command) {
    TemplateManager.defaultAdapter = PebbleAdapter(false, 1 * 1024 * 1024)

    val url = urlParameter(command)
    val urlString = url.toString()
    val serverSettings = HttpServerSettings(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), zip = true)
    val protocol = serverSettings.protocol.toString().lowercase()
    val hostName = serverSettings.bindAddress.hostName
    val bindPort = serverSettings.bindPort
    val base = "$protocol://$hostName:$bindPort"
    val scriptSources = "https://unpkg.com/rapidoc/ 'unsafe-inline'"
    val adapter = NettyServerAdapter(executorThreads = 4, soBacklog = 1024)

    server = serve(adapter, serverSettings) {
        after("*") { addHeaders(scriptSources) }

        get("/openapi.{format}") { getReformattedData(spec) }
        get("/schema.{format}") { getReformattedData(schema) }
        get("/cv.{format}") { getReformattedData(urlString) }
        get("/cv") { renderCv(urlString, base) }
        get(callback = UrlCallback(URL(mainPage)))
    }

    if (command.propertyValueOrNull<Boolean>("b") == true)
        Desktop.getDesktop().browse(URI("http://localhost:${server.runtimePort}/cv"))
}

private fun HttpContext.addHeaders(scriptSources: String): HttpContext {
    val contentSecurityValues = listOf("script-src $scriptSources", "object-src none")
    val contentSecurityPolicy = Header("content-security-policy", contentSecurityValues)
    val xUaCompatible = Header("x-ua-compatible", "IE=edge")

    return send(headers = response.headers + contentSecurityPolicy + xUaCompatible)
}

private fun HttpContext.getReformattedData(url: String): HttpContext {
    val data = URL(url).parseMap()
    val format = pathParameters.require("format")
    val mediaType = mediaTypeOfOrNull(format)
        ?: return badRequest("Invalid extension (only 'yaml', 'yml', 'toml' and 'json'): $format")

    return ok(data.serialize(mediaType), contentType = ContentType(mediaType))
}

private fun HttpContext.renderCv(cvUrl: String, base: String): HttpContext {
    val url = URL(cvUrl)
    val cvData = url.parseMap()
    val valid = validate(cvData)
    if (!valid)
        return badRequest("CV does not complain with schema")

    val cv = decode(cvData, url)
    val template = cv.getPath<Collection<String>>("templates")?.firstOrNull() ?: defaultTemplate
    val templateUrl = resolve(URL(template), url)
    val variables = cv.getPath<Map<String, Any>>("variables") ?: emptyMap()
    val templateContext = variables + mapOf("cv" to cv, "base" to base)

    return template(templateUrl, templateContext + callContext())
}

private fun resolve(url: URL, base: URL): URL {
    return if (url.protocol == "file") {
        val cvBase = Path.of(base.path).parent
        cvBase.resolve(Path.of(url.path)).toUri().toURL()
    } else {
        url
    }
}

private fun decode(data: Map<*, *>, url: URL): Map<*, *> {
    val fullMap = mergeIncludes(data, url)
    return toCamelCase(fullMap) as Map<*, *>
}

private fun mergeIncludes(data: Map<*, *>, base: URL): Map<*, *> {
    val includes = data.getPath<List<String>>("Resources")
        ?.map(::URL)
        ?.map { resolve(it, base) }
        ?: emptyList()

    val allMaps = includes.map(URL::parseMap) + listOf(data)
    return merge(allMaps)
}

private fun toCamelCase(data: Any?): Any? =
    when (data) {
        is Map<*, *> ->
            data.mapKeys { it.key.toString().split(" ").wordsToCamel() }
                .mapValues { (k, v) ->
                    if (k.lowercase() == "variables") v
                    else toCamelCase(v)
                }

        is List<*> ->
            data.map { toCamelCase(it) }

        else ->
            data
    }

private fun validate(data: Map<*, *>): Boolean {
    val schemaMap = URL(schema).parseMap()
    val jsonSchema = JsonSchema.of(JsonObject.mapFrom(schemaMap))
    val options = JsonSchemaOptions().setDraft(DRAFT7).apply { baseUri = "file:./" }
    val validator = Validator.create(jsonSchema, options)

    return validator.validate(JsonObject.mapFrom(data)).valid
}
