package application.backwardCompatibility

import picocli.CommandLine.Option

class BackwardCompatibilityCheckOptions {
    @Option(
        names = ["--base-branch"],
        description = ["Base branch to compare the changes against", "Default value is the local origin HEAD of the current branch"],
        required = false
    )
    var baseBranch: String? = null

    @Option(
        names = ["--target-path"],
        description = ["Specify the file or directory to limit the backward compatibility check scope. If omitted, all changed files will be checked."],
        required = false
    )
    var targetPath: String? = null

    @Option(
        names = ["--repo-dir"],
        description = ["The directory of the repository in which to run the backward compatibility check.", "If not provided, the check will run in the current working directory."],
        required = false
    )
    var repoDir: String? = null

    @Option(names = ["--debug"], description = ["Write verbose logs to console for debugging"])
    var debugLog: Boolean? = null

    @Option(
        names = ["--strict"],
        description = [
            "In strict mode, irrespective of the API's usage, if a change to an API breaks backward compatibility then it would result in a failure.",
            "When this flag is not specified, backward breaking changes to APIs that have no usages will only generate warnings and won't cause the check to fail.",
            "This flag is only applicable when using Specmatic Insights.",
        ]
    )
    var strictMode: Boolean? = null
}
