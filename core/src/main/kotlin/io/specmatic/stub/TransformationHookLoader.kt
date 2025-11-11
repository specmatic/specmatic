package io.specmatic.stub

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.logger

/**
 * Loader for transformation hooks.
 *
 * This class loads hook commands from configuration and registers them
 * with the HttpStub instance.
 */
object TransformationHookLoader {
    private const val REQUEST_HOOK_KEY = "transform_stub_request"
    private const val RESPONSE_HOOK_KEY = "transform_stub_response"

    /**
     * Load and register transformation hooks from configuration.
     *
     * The hooks are expected to be in the format:
     * ```yaml
     * hooks:
     *   transform_stub_request: <shell command>
     *   transform_stub_response: <shell command>
     * ```
     *
     * @param config The Specmatic configuration
     * @param httpStub The HttpStub instance to register the hooks with
     */
    fun loadHooksFromConfig(config: SpecmaticConfig, httpStub: HttpStub) {
        val hooks = SpecmaticConfig.getHooks(config)

        // Load request transformation hook
        hooks[REQUEST_HOOK_KEY]?.let { command ->
            if (command.isNotBlank()) {
                try {
                    val hook = CommandRequestTransformationHook(command)
                    httpStub.registerRequestTransformationHook(hook)
                    logger.log("Loaded request transformation hook: $command")
                } catch (e: Throwable) {
                    logger.log(e, "Error loading request transformation hook")
                }
            }
        }

        // Load response transformation hook
        hooks[RESPONSE_HOOK_KEY]?.let { command ->
            if (command.isNotBlank()) {
                try {
                    val hook = CommandResponseTransformationHook(command)
                    httpStub.registerResponseTransformationHook(hook)
                    logger.log("Loaded response transformation hook: $command")
                } catch (e: Throwable) {
                    logger.log(e, "Error loading response transformation hook")
                }
            }
        }
    }
}
