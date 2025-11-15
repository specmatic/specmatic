package io.specmatic.stub

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.logger

/**
 * Loader for transformation hooks.
 *
 * This class loads hook commands from configuration and registers them
 * with the HttpStub or Proxy instance.
 */
object TransformationHookLoader {
    // Stub hooks
    private const val STUB_REQUEST_HOOK_KEY = "decode_request_from_consumer"
    private const val STUB_RESPONSE_HOOK_KEY = "encode_response_to_consumer"

    // Proxy hooks
    private const val PROXY_REQUEST_HOOK_KEY = "decode_request_from_consumer"
    private const val PROXY_RESPONSE_HOOK_KEY = "decode_response_from_provider"

    /**
     * Load and register transformation hooks from configuration for HttpStub.
     *
     * The hooks are expected to be in the format:
     * ```yaml
     * hooks:
     *   decode_request_from_consumer: <shell command>
     *   encode_response_to_consumer: <shell command>
     * ```
     *
     * @param config The Specmatic configuration
     * @param httpStub The HttpStub instance to register the hooks with
     */
    fun loadHooksFromConfig(config: SpecmaticConfig, httpStub: HttpStub) {
        val hooks = SpecmaticConfig.getHooks(config)

        // Load request transformation hook
        hooks[STUB_REQUEST_HOOK_KEY]?.let { command ->
            if (command.isNotBlank()) {
                try {
                    val hook = CommandRequestTransformationHook(command)
                    httpStub.registerRequestTransformationHook(hook)
                    logger.log("Loaded stub request transformation hook: $command")
                } catch (e: Throwable) {
                    logger.log(e, "Error loading stub request transformation hook")
                }
            }
        }

        // Load response transformation hook
        hooks[STUB_RESPONSE_HOOK_KEY]?.let { command ->
            if (command.isNotBlank()) {
                try {
                    val hook = CommandResponseTransformationHook(command)
                    httpStub.registerResponseTransformationHook(hook)
                    logger.log("Loaded stub response transformation hook: $command")
                } catch (e: Throwable) {
                    logger.log(e, "Error loading stub response transformation hook")
                }
            }
        }
    }

    /**
     * Load and register transformation hooks from configuration for Proxy.
     *
     * The hooks are expected to be in the format:
     * ```yaml
     * hooks:
     *   decode_request_from_consumer: <shell command>
     *   decode_response_from_provider: <shell command>
     * ```
     *
     * @param config The Specmatic configuration
     * @param proxy The Proxy instance to register the hooks with
     */
    fun loadHooksFromConfigForProxy(config: SpecmaticConfig, proxy: io.specmatic.proxy.Proxy) {
        val hooks = SpecmaticConfig.getHooks(config)

        // Load request transformation hook
        hooks[PROXY_REQUEST_HOOK_KEY]?.let { command ->
            if (command.isNotBlank()) {
                try {
                    val hook = CommandRequestTransformationHook(command)
                    proxy.registerRequestTransformationHook(hook)
                    logger.log("Loaded proxy request transformation hook: $command")
                } catch (e: Throwable) {
                    logger.log(e, "Error loading proxy request transformation hook")
                }
            }
        }

        // Load response transformation hook
        hooks[PROXY_RESPONSE_HOOK_KEY]?.let { command ->
            if (command.isNotBlank()) {
                try {
                    val hook = CommandResponseTransformationHook(command)
                    proxy.registerResponseTransformationHook(hook)
                    logger.log("Loaded proxy response transformation hook: $command")
                } catch (e: Throwable) {
                    logger.log(e, "Error loading proxy response transformation hook")
                }
            }
        }
    }
}
