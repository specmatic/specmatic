# WSDL contracts

The XML Schema terms used to describe the payloads of WSDL operations.

## Language

**Inline simple type**:
A simple type defined directly inside an element declaration. It applies to that element and has no reusable schema type name.
_Avoid_: Inline complex type

**Named simple type**:
A simple type declared with a schema type name that element declarations can reference.
_Avoid_: Element name

**Chameleon schema**:
An XSD document without a target namespace that adopts the target namespace of a schema including it. The same document can be interpreted under different including namespaces.
_Avoid_: Namespace-free service

**Effective schema namespace**:
The namespace used for a schema document in a particular loading context: its declared target namespace, or the including schema's namespace for a chameleon schema.
_Avoid_: XML prefix
