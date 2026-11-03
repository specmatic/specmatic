# Preserve XML particle structure and occurrence ranges

WSDL conversion currently collapses element occurrence bounds to once, optional, or multiple and flattens sequence and all groups, losing constraints required to interpret the schema. Use a shared occurrence-range model for elements, wildcards, and model groups, retaining group structure and named group references so generation, request matching, response validation, and compatibility use the same semantics. This replaces a narrow zero-occurrence fix and separate per-kind range handling with full minOccurs/maxOccurs support; omitted bounds default to one, and invalid ranges or XSD 1.0 compositor restrictions fail during loading with declaration and source context.

Ordinary generation follows StringPattern's non-regex length policy: choose five clamped to the effective occurrence interval, with positive boundary variants and negative one-below/one-above variants where applicable. XSD occurrence defaults still apply before generation, so omitted bounds generate one occurrence. Compatibility must preserve acceptance of every payload allowed by the encompassed contract, considering group structure as well as numeric range containment.

Matching must accept valid empty alternatives, terminate safely for repeatable empty groups, and satisfy the complete surrounding structure. The matching algorithm remains an implementation choice; this requirement does not prescribe backtracking everywhere.

Do not downsample occurrence bounds or introduce a generation limit. Preserve the full declared ranges for generation, matching, and compatibility.

Keep the existing Result contract and the request matches / response encompasses paths; this work does not introduce a new compatibility result model or reporting policy. Legacy specmatic_occurs="optional" and "multiple" remain supported as 0..1 and 0..unbounded respectively, with compatibility aligned to those ranges, including multiple encompassing once.

These design decisions are accepted, and implementation is authorized. Verification uses WSDL loading through generated SOAP payloads and request/response validation, with JDK XSD validation as an independent oracle, plus public matches/encompasses APIs for range and legacy-syntax compatibility.
