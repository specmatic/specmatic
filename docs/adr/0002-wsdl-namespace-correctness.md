# Enforce WSDL namespace correctness by default

WSDL-derived contracts will enforce namespace correctness by default across request matching, response validation, generation, and backward compatibility checks. Prefix changes with equal resolved namespace URIs remain equivalent; messages previously accepted with missing or incorrect namespaces will fail, and the behavior change will be documented. This deliberately corrects permissive validation rather than preserving it as the default.

The fix includes qualified-attribute generation and global-element qualification, local qualification defaults and explicit form overrides, references, inherited bindings, default namespaces and resets, imports/includes, and existing wildcard and polymorphism paths. Remote fetching, redefine/override, and unrelated datatype support remain outside scope unless they block namespace correctness in an already-supported construct.

Gherkin is being phased out: preserving its namespace behavior is optional where the simplest sound implementation changes it, and it must not force additional architecture solely for legacy compatibility.

Generated XML must be namespace-correct. Preserve usable existing prefixes where practical, but fixed generated prefix names, declaration placement and byte-for-byte output stability are not guarantees.
