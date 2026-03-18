import io.swagger.v3.oas.models.OpenAPI

class RouteMatcher(openApi: OpenAPI) {
    private val routes: Set<Pair<String, String>> = openApi.paths.orEmpty()
        .flatMap { (path, item) ->
            item.readOperationsMap().orEmpty().keys.map { method ->
                method.name.uppercase() to path
            }
        }.toSet()

    fun allRoutes(): Set<Pair<String, String>> = routes

    fun matchingTemplate(method: String, path: String): String? =
        routes.firstOrNull { (m, template) ->
            m.equals(method, ignoreCase = true) && templateMatches(template, path)
        }?.second

    fun matches(method: String, path: String): Boolean = matchingTemplate(method, path) != null

    fun exercisedRoutes(captures: List<HttpCapture>): Set<Pair<String, String>> =
        captures.mapNotNull { capture ->
            matchingTemplate(capture.method, capture.path)?.let { capture.method.uppercase() to it }
        }.toSet()

    private fun templateMatches(template: String, path: String): Boolean {
        val templateParts = template.split("/")
        val pathParts = path.split("/")
        if (templateParts.size != pathParts.size) return false
        return templateParts.zip(pathParts).all { (t, p) ->
            t.startsWith("{") && t.endsWith("}") || t == p
        }
    }
}
