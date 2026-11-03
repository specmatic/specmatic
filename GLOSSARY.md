# WSDL contracts

The XML Schema terms used to describe the payloads of WSDL operations.

## Language

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
