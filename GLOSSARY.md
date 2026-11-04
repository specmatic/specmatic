# Specmatic contracts

Terms used to describe API contracts and their compatibility checks.

## WSDL contracts

**Inline simple type**:
A simple type defined directly inside an element declaration. It applies to that element and has no reusable schema type name.
_Avoid_: Inline complex type

**Named simple type**:
A simple type declared with a schema type name that element declarations can reference.
_Avoid_: Element name

## Backward compatibility

**Discovery candidate**:
A document considered when finding specifications affected by a changed file. It may be a complete specification or a schema fragment.

**Checked specification**:
An API specification selected for a backward compatibility check. Schema fragments and ordinary configuration or report files are not checked specifications.

**Schema fragment**:
A document containing reusable schema definitions referenced by a specification or another fragment, without itself being a complete API specification.
