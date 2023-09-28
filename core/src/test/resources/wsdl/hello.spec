Feature: simpleService

    Scenario: SimpleOperation
        When POST /SOAPService/SimpleSOAP
        And enum SoapAction (string) values "http://specmatic.in/SOAPService/SimpleOperation",http://specmatic.in/SOAPService/SimpleOperation
        And request-header SOAPAction (SoapAction)
        And request-body
        """
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleRequest>(string)</SimpleRequest></soapenv:Body></soapenv:Envelope>
        """
        Then status 200
        And response-body
        """
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
        """
