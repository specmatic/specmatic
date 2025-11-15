package io.specmatic.stub

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.logger

/**
 * Loader for codec hooks.
 *
 * This class loads hook commands from configuration and registers them
 * with the HttpStub or Proxy instance.
 */
object CodecHookLoader {
    private const val DECODE_REQUEST_FROM_CONSUMER = "decode_request_from_consumer"
    private const val ENCODE_RESPONSE_TO_CONSUMER = "encode_response_to_consumer"
    private const val DECODE_RESPONSE_FROM_PROVIDER = "decode_response_from_provider"

    /**
     * Load and register codec hooks from configuration for HttpStub.
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
    fun loadCodecHooksFromConfig(config: SpecmaticConfig, httpStub: HttpStub) {
        val hooks = config.getHooks()

        // Load request codec hook
        hooks[DECODE_REQUEST_FROM_CONSUMER]?.let { command ->
            if (command.isNotBlank()) {
                try {
                    val hook = CommandRequestCodecHook(command)
                    httpStub.registerRequestCodecHook(hook)
                    logger.log("Loaded stub request codec hook: $command")
                } catch (e: Throwable) {
                    logger.log(e, "Error loading stub request codec hook")
                }
            }
        }

        // Load response codec hook
        hooks[ENCODE_RESPONSE_TO_CONSUMER]?.let { command ->
            if (command.isNotBlank()) {
                try {
                    val hook = CommandResponseCodecHook(command)
                    httpStub.registerResponseCodecHook(hook)
                    logger.log("Loaded stub response codec hook: $command")
                } catch (e: Throwable) {
                    logger.log(e, "Error loading stub response codec hook")
                }
            }
        }
    }

    /**
     * Load and register codec hooks from configuration for Proxy.
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
    fun loadCodecHooksFromConfigForProxy(config: SpecmaticConfig, proxy: io.specmatic.proxy.Proxy) {
        val hooks = SpecmaticConfig.getHooks(config)

        // Load request codec hook
        hooks[DECODE_REQUEST_FROM_CONSUMER]?.let { command ->
            if (command.isNotBlank()) {
                try {
                    val hook = CommandRequestCodecHook(command)
                    proxy.registerRequestCodecHook(hook)
                    logger.log("Loaded proxy request codec hook: $command")
                } catch (e: Throwable) {
                    logger.log(e, "Error loading proxy request codec hook")
                }
            }
        }

        // Load response codec hook
        hooks[DECODE_RESPONSE_FROM_PROVIDER]?.let { command ->
            if (command.isNotBlank()) {
                try {
                    val hook = CommandResponseCodecHook(command)
                    proxy.registerResponseCodecHook(hook)
                    logger.log("Loaded proxy response codec hook: $command")
                } catch (e: Throwable) {
                    logger.log(e, "Error loading proxy response codec hook")
                }
            }
        }
    }
}
