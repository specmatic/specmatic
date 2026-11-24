# SOAP header maintenance notes

Keep block presence, XSD content validation and envelope Header presence separate.
Optional-by-default is a deliberate Specmatic policy for WSDL 1.1 ambiguity.
Do not restore binding `minOccurs` or port-type `required` shortcuts: a fixture
using those attributes does not establish the presence policy.

Claim scope belongs to the selected service instance, not its shared binding.
When changing import resolution or endpoint selection, keep the endpoint,
binding and claim identity aligned. Basic Profile 1.1 messaging requirements
apply to SOAP 1.1; matching version numbers alone do not establish applicability.
Leave claims that do not establish requiredness silent: their presence alone is
not a WSDL error.

Keep SOAP control metadata in `XMLTypeData` fields. Consume XML-node transport
markers at the conversion boundary so pattern attribute maps and visitors cannot
expose them as XML attributes. Preserve these fields through type resolution
and both derivation paths.

Schema-generated empty Header wrappers may be omitted; explicit examples must
retain their chosen representation and meaningful attributes. Keep this handling
SOAP-specific so generic XML matching is unaffected.

Use the public WSDL loading, request/response matching, generation and
compatibility seams covered by `SOAPHeader*Test` in the
[WSDL tests](../core/src/test/kotlin/io/specmatic/core/wsdl/). Check both derivation
overloads, shared-binding port isolation, supplied-content negatives and explicit
empty-wrapper examples when changing these paths.
