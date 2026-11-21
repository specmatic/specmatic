# SOAP header presence in WSDL 1.1

Specmatic treats a declared SOAP header block as optional unless a supported,
applicable service-instance conformance claim makes it required. This is
Specmatic's policy for the presence behavior that WSDL 1.1 leaves unspecified;
it is not a requirement that WSDL makes headers optional.

For example, declaring a `Session` block on a binding's `wsdl:input` allows a
caller to omit `Session`. If the caller supplies it, Specmatic validates its
entire XSD content, including required children, value constraints and XML
namespaces. Output declarations independently control response validation.
A request declaration does not declare the same block on the response. These
rules also apply to header-only messages with an empty SOAP Body.

When all declared blocks are optional, both an absent SOAP `Header` and an empty
`Header` are accepted. Generated positive tests exercise block presence and
absence. Generated requests and mock responses omit the wrapper when no blocks
are selected, and include it when blocks are selected. Explicit examples retain
their chosen representation, including an empty wrapper and its attributes.

## Basic Profile 1.1 service-instance claims

For a SOAP 1.1 binding, a claim using the exact profile identifier
`http://ws-i.org/profiles/basic/1.1` under the selected port's WSDL documentation
makes its declared input and output header blocks required (Basic Profile 1.1,
R2738):

```xml
<wsdl:service name="ExampleService">
  <wsdl:port name="ExamplePort" binding="tns:ExampleBinding">
    <wsdl:documentation>
      <wsi:Claim xmlns:wsi="http://ws-i.org/schemas/conformanceClaim/"
                 conformsTo="http://ws-i.org/profiles/basic/1.1"/>
    </wsdl:documentation>
    <soap:address location="http://localhost:9000/example"/>
  </wsdl:port>
</wsdl:service>
```

Here `wsdl` denotes `http://schemas.xmlsoap.org/wsdl/` and `soap` denotes
`http://schemas.xmlsoap.org/wsdl/soap/`. Equivalent namespace prefixes work.
The claim follows the selected port's binding references, including imports;
it does not make headers required for another port sharing the binding.

Description-only claims on a binding, port type, its operations, or a message
do not establish service-instance header requiredness. Other profile versions
are not inferred from similar identifiers or SOAP envelope versions. In
particular, Basic Profile 1.1's messaging requirements apply to SOAP 1.1; the
same claim on a SOAP 1.2 binding produces a warning and retains the optional
fallback.

Unsupported profiles and attachment scopes produce warnings in strict and
lenient parsing. A warning identifies the claim's source and attachment and
explains that headers remain optional unless another supported applicable
service-instance claim requires them. An unsupported claim never cancels a
supported applicable claim. This handling does not assess full WS-I profile
conformance.

## Compatibility and migration

Binding-level `soap:header/@minOccurs` and port-type header `required` shortcuts
are no longer interpreted. XSD occurrence constraints inside the block remain
in force. Use an applicable service-instance claim when the endpoint makes that
profile commitment; otherwise omission is allowed.

Backward compatibility checks retain these presence rules. Making a request
header required narrows accepted requests; making a response header optional
widens possible responses and can break existing consumers.

Additional undeclared header blocks still fail matching. This change does not
implement undeclared-header support, WS-Security, WS-Policy, or SOAP processing
rules such as `mustUnderstand`.

References: [WSDL 1.1 SOAP headers](https://www.w3.org/TR/wsdl.html#_soap:header),
[Basic Profile 1.1](https://ws-i.org/Profiles/BasicProfile-1.1.html), and
[Conformance Claim Attachment Mechanisms 1.0](https://www.ws-i.org/Profiles/ConformanceClaims-1.0-2004-11-15.html).
