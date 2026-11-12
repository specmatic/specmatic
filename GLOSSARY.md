# Specmatic contracts

Terms used to describe API contracts and their compatibility checks.

## WSDL contracts

**WSDL document**:
A document containing WSDL declarations for a target namespace. Multiple documents can contribute declarations to the same target namespace and reference one another through WSDL imports.

**WSDL definition identity**:
The combination of a declaration's kind, target namespace URI and local name. A message and a binding with the same namespace and local name have different identities.

**Inline simple type**:
A simple type defined directly inside an element declaration. It applies to that element and has no reusable schema type name.
_Avoid_: Inline complex type

**Named simple type**:
A simple type declared with a schema type name that element declarations can reference.
_Avoid_: Element name

**Occurrence range**:
The minimum and maximum number of times an XML Schema particle may occur, expressed by `minOccurs` and `maxOccurs`. An unbounded maximum places no upper limit on the number of occurrences.
_Avoid_: Length constraint

**Particle**:
An XML Schema element, wildcard, or model group together with its occurrence range. Repeating a model-group particle repeats the group as a whole.
_Avoid_: XML node

**Model group**:
A collection of particles governed by sequence, choice, or all semantics. Sequence preserves order, choice selects an alternative, and all permits its members in any order under XML Schema's restrictions.
_Avoid_: Element group

**Chameleon schema**:
An XSD document without a target namespace that adopts the target namespace of a schema including it. The same document can be interpreted under different including namespaces.
_Avoid_: Namespace-free service

**Effective schema namespace**:
The namespace used for a schema document in a particular loading context: its declared target namespace, or the including schema's namespace for a chameleon schema.
_Avoid_: XML prefix

**Namespace-correct WSDL contract**:
A WSDL contract whose XML names obey the namespace and qualification requirements of its declarations throughout request matching, response validation, generation, and contract compatibility. Different prefixes bound to the same namespace identify equivalent names.

**Global payload element**:
A payload element declared directly in an XML Schema, identified by its declaration name and effective schema namespace. Local element qualification defaults do not remove its namespace.

**Local element qualification**:
Whether an element declared within a schema type belongs to its schema namespace or has no namespace. An explicit `form` setting takes precedence over that declaration's source schema default.

## Backward compatibility

**Discovery candidate**:
A document considered when finding specifications affected by a changed file. It may be a complete specification or a schema fragment.

**Checked specification**:
An API specification selected for a backward compatibility check. Schema fragments and ordinary configuration or report files are not checked specifications.

**Schema fragment**:
A document containing reusable schema definitions referenced by a specification or another fragment, without itself being a complete API specification.
