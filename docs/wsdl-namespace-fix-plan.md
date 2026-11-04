# WSDL namespace correctness — working plan

Status: implemented and verified using the implement skill, test-driven slices at the agreed public boundaries, and independent standards and spec reviews.

## Accepted outcomes

- Match and validate WSDL requests and responses using resolved namespace URI and local name. Equivalent prefixes pass; missing or incorrect namespace URIs fail.
- Generate correctly qualified requests and responses, including global payload elements and qualified attributes.
- Detect namespace changes in backward compatibility checks, using the existing request/response compatibility directions.
- Cover inherited bindings, default namespaces and resets, local form defaults and overrides, global references, imports/includes, and existing wildcard and polymorphism paths.
- Keep remote fetching, redefine/override and unrelated datatype support outside scope. Leave Enterprise's separate XSD parser and its established behavior unchanged. Gherkin compatibility is optional: choose the simplest sound implementation and document any resulting behavior changes.
- Guarantee namespace-correct generated output, preserving usable prefixes where practical without fixed prefix names or byte-for-byte output guarantees.

## Evidence and baseline

Initial local inspection is at cbc0f29975ecf685e0c4814f3c9bdae429e4a767. This contains schema aggregation and inline-simple-type fixes but predates the include and occurrence merges recorded on the WSDL Support Page. Reconcile with the current merged baseline before implementation; retain their source-context and occurrence behavior.

At the original inspected baseline, `XMLPattern.matchNamespaces` returned success unconditionally. Element matching and compatibility compared local names, while attribute matching and compatibility used lexical keys. `AttributeElement` stored namespace metadata separately but built attribute names and duplicate checks from local names. `XMLNode` already resolved inherited namespaces and distinguished unprefixed attributes from elements. Existing legacy XMLPattern tests intentionally permitted unbound prefixes.

## Implementation sequence

1. Establish the current baseline and trace WSDL patterns through copies, references, deferred resolution, visitors, examples and Enterprise consumers. Pin down how WSDL namespace enforcement survives these paths while leaving the separate Enterprise XSD parser and its behavior unchanged. Do not add complexity solely to preserve Gherkin behavior.
2. Represent semantic XML identity as namespace URI plus local name, separate from serialization prefix. Distinguish a required absent namespace from legacy namespace-unspecified behavior. Use expanded identity for attribute maps and duplicate checks so two attributes with the same local name in different namespaces remain distinct.
3. Derive namespace requirements from declaration context: global declarations use their target namespace; local declarations use explicit form or source-specific defaults; references use the referenced declaration. Preserve include adoption and imported schema context.
4. Apply those identities consistently to element/attribute matching, optional and repeated child selection, groups, SOAP headers, wildcards, type selection and encompasses checks. Validate namespace well-formedness at strict WSDL ingress, including unresolved or reserved prefix misuse and duplicate expanded attribute names, without globally replacing the permissive legacy parser. Preserve SOAP metadata and xsi:type handling by resolved URI.
5. Make generation bind names to the required URI, reuse suitable prefixes, allocate collision-free prefixes for qualified attributes, and emit default-namespace resets for unqualified children when needed. Preserve QName-valued xsi:type bindings and namespace metadata during reconstruction.
6. Add readable namespace mismatch diagnostics with expected/actual URI, local name and existing schema locations. Document newly rejected invalid messages and the legacy boundary.

## Verification

Use an independent JDK XSD validator for generated payloads and representative valid/invalid namespace mutations; self-matching alone is insufficient. Cover request and response flows through the CLI/runtime, contract compatibility in both directions, equivalent prefixes/default-vs-prefixed forms, wrong/missing URIs, inherited/shadowed bindings, namespace resets, attributes sharing local names, form overrides, global refs, same-namespace schema aggregation, includes, groups/occurrences, wildcards and xsi:type. Run focused XML/WSDL tests, relevant Enterprise consumer checks, then required repository checks with Gradle tests serialized.

## Compatibility boundary

Gherkin behavior may change where that simplifies the implementation. This does not authorize changing Enterprise's separate XSD parser or silently imposing namespace requirements on its existing patterns. An explicit namespace requirement distinguishes required no-namespace from unspecified constraints; its exact representation remains an implementation choice, not a commitment to a new global compatibility switch.

Enterprise async XSDParser constructs XMLTypeData without namespaceUri, so null cannot safely become a universal no-namespace constraint. WSDL builds scenarios directly rather than requiring a Gherkin roundtrip, although SOAP envelope construction reparses XML shells; constraint propagation must cover that reconstruction. ComplexTypedSOAPPayload also derives payload namespace from a nonblank lexical prefix, losing default-namespace identity.

Enterprise audit: the only direct production XMLPattern/XMLTypeData construction found outside Core is the separate async XSDParser. Other consumers exist: Studio ServerConfig loads contracts via Core's parseContractFileToFeature, MockServerManager handles SoapApiConfig, ConfigExecutor runs SOAP entries, and NestedMatcherValueVisitor traverses/rebuilds XMLNode attributes and children. These should inherit the WSDL fix and receive targeted regression coverage, without changing the standalone XSD parser. Preserve namespace scope and constraints through shared visitor reconstruction.

## Completion criteria

- Equivalent XML expanded names match regardless of prefix spelling or declaration placement; incorrect or missing required namespaces fail with actionable diagnostics.
- Qualification follows declaration scope and source defaults, including global declarations under unqualified local defaults.
- Both qualified and unqualified attributes work; different expanded names sharing a local name remain distinct. Namespace-malformed input fails on WSDL paths.
- Generated requests and responses are independently XSD-valid for namespace-focused fixtures, including default namespaces and resets, and work through runtime/CLI flows.
- Prefix-only contract edits remain compatible; namespace/qualification changes are assessed with the established compatibility directions and wildcard constraints.
- Includes, schema aggregation, occurrences/groups, polymorphism, SOAP header handling and the standalone Enterprise XSD path retain their intended behavior.

Implementation baseline: 9a485f72be139a5e6cf250c297531a6a01f25f10 from origin/main, including the merged include and occurrence fixes. The earlier baseline was fast-forwarded before implementation.

## Verification results

- All 47 namespace regression cases passed through public WSDL loading, matching, compatibility and generation interfaces. Generation uses independent JDK XSD validation, including qualified attributes, local form overrides, namespace URI escaping, default bindings, schema imports and supported nested substitution references.
- The final clean repository-wide run executed 9,236 tests: 13 skipped and seven unrelated HTTP port-collision failures. A one-fork, one-project rerun passed all 125 tests in the affected HTTP class, including every failed case. All 9,223 non-skipped cases are therefore verified across the full run and serialized rerun. No namespace failures remained.
- Test JVMs disabled JDK HTTP keep-alive and set a 60-second read timeout after an existing RestTemplate test stalled on a reused connection. These are ignored local verification settings; production and repository test configuration are unchanged.
- Core check passed with its test dependency excluded because tests had already been verified above. All local Maven publications and the executable JAR built successfully.
- The rebuilt CLI returned 200 for equivalent prefixes and default namespaces, and 400 for wrong/missing namespaces, unqualified required attributes and wrongly qualified children. The fixture includes a chameleon schema and a namespace URI containing `&`. Both successful request and generated response payloads independently passed the JDK XSD validator. The CLI contract test against that mock passed.
- Against the verified updated Maven-local Core artifact, 24 standalone XSD parser/matcher tests and 21 matcher/visitor tests passed. The separate consumer repository is unchanged. Temporary verification settings pinned Cucumber messages to Core's resolved version, 28.1.0, and restricted Specmatic repositories to their own groups to avoid unrelated credential-protected metadata queries.
- Fresh read-only standards and spec reviews have no unresolved findings.
