Feature: StockQuoteService

    Scenario: GetLastTradePrice
        Given type GetLastTradePrice_SOAPPayload_Input
        """
        <SPECMATIC_TYPE>
            <tickerSymbol specmatic_nillable="true">(string)</tickerSymbol>
        </SPECMATIC_TYPE>
        """
        And type GetLastTradePrice_SOAPPayload_Output
        """
        <SPECMATIC_TYPE>
            <price>(number)</price>
        </SPECMATIC_TYPE>
        """
        When POST /stockquote
        And enum SoapAction (string) values "http://example.com/GetLastTradePrice",http://example.com/GetLastTradePrice
        And request-header SOAPAction (SoapAction)
        And request-body
        """
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Body><TradePriceRequest specmatic_type="GetLastTradePrice_SOAPPayload_Input"/></soapenv:Body></soapenv:Envelope>
        """
        Then status 200
        And response-body
        """
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Body><TradePrice specmatic_type="GetLastTradePrice_SOAPPayload_Output"/></soapenv:Body></soapenv:Envelope>
        """
