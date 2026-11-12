# Separate BCC discovery from check eligibility

Backward compatibility discovery traverses schema fragments as well as complete specifications. Keep its inexpensive, transitive filename matching, including matches outside the target path, but select only actual specifications for checks and console listings. Filtering traversal to complete specifications would lose shared-schema chains. Discovery chains are not displayed; existing breakage source locations remain intact.

OpenAPI check eligibility belongs in the OSS OpenAPI command. The shared base supplies a default that preserves Enterprise protocol eligibility and discovery overrides. CTRF and HTML continue to describe checked specifications and their operations, without separate records for fragments or unrelated configuration files.

Final OpenAPI eligibility uses the V3 parser’s `readLocation()` result and requires a non-null OpenAPI version. This retains Swagger parsing without a separate YAML/JSON identity parse or the `read()` extension fallback that can admit ordinary reports. Discovery continues using its parser path so headerless fragments remain traversable.
