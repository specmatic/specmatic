package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import io.specmatic.conversions.*
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.value.*
import io.specmatic.stub.HttpStub

class PostmanKtTests {
    @Test
    fun `should be able to guess a boolean value`() {
        assertThat(guessType(StringValue("true"))).isEqualTo(BooleanValue(true))
        assertThat(guessType(StringValue("false"))).isEqualTo(BooleanValue(false))
    }

    @Test
    fun `should be able to guess a number value`() {
        assertThat(guessType(StringValue("10"))).isEqualTo(NumberValue(10))
    }

    @Test
    fun `should be able to guess a JSON value`() {
        val json = """{"one": 1}"""
        assertThat(guessType(StringValue(json))).isEqualTo(parsedJSON(json))
    }

    @Test
    fun `should be able to guess a XML value`() {
        val xml = """<data/>"""
        assertThat(guessType(StringValue(xml))).isEqualTo(parsedValue(xml))
    }

    @Test
    fun `should be able to extract a URL out of a Postman json object`() {
        val url = urlFromPostmanValue(parsedValue("""{"raw": "https://localhost/path"}"""))
        assertThat(url.toString()).isEqualTo("https://localhost/path")
    }

    @Test
    fun `should be able to extract a URL out of a Postman string`() {
        val url = urlFromPostmanValue(StringValue("https://localhost/path"))
        assertThat(url.toString()).isEqualTo("https://localhost/path")
    }

    @Test
    fun `should be able to get a URL out of a Postman url missing the protocol`() {
        val url = urlFromPostmanValue(StringValue("localhost/path"))
        assertThat(url.toString()).isEqualTo("http://localhost/path")
    }

    @Test
    fun `should convert a Postman request with query parameters`() {
        val postmanJSON = """{
                "method": "POST",
                "header": [
                ],
                "url": {
                    "raw": "https://localhost?one=1"
                }
            }""".trimIndent()

        val request = postmanItemRequest(parsedJSON(postmanJSON) as JSONObjectValue)

        assertThat(request.first).isEqualTo("https://localhost")
        assertThat(request.second.queryParams).isEqualTo(QueryParameters(listOf("one" to "1")))
    }

    @Test
    fun `should convert a Postman request with a path`() {
        val postmanJSON = """{
                "method": "POST",
                "header": [
                ],
                "url": {
                    "raw": "https://localhost/path"
                }
            }""".trimIndent()

        val request = postmanItemRequest(parsedJSON(postmanJSON) as JSONObjectValue)

        assertThat(request.first).isEqualTo("https://localhost")
        assertThat(request.second.path).isEqualTo("/path")
    }

    @Test
    fun `should convert a Postman request with headers`() {
        val postmanJSON = """{
                "method": "POST",
                "header": [
                    {"key": "X-Header", "value": "10"}
                ],
                "url": {
                    "raw": "https://localhost"
                }
            }""".trimIndent()

        val request = postmanItemRequest(parsedJSON(postmanJSON) as JSONObjectValue)

        assertThat(request.first).isEqualTo("https://localhost")
        assertThat(request.second.headers).isEqualTo(mapOf("X-Header" to "10"))
    }

    @Test
    fun `should convert a Postman request with request body`() {
        val postmanJSON = """{
                "method": "POST",
                "header": [
                ],
                "url": {
                    "raw": "https://localhost"
                },
                "body": {
                    "mode": "raw",
                    "raw": "data"
                }
            }""".trimIndent()

        val request = postmanItemRequest(parsedJSON(postmanJSON) as JSONObjectValue)

        assertThat(request.first).isEqualTo("https://localhost")
        assertThat(request.second.bodyString).isEqualTo("data")
    }

    @Test
    fun `should convert a Postman request with form fields`() {
        val postmanJSON = """{
                "method": "POST",
                "header": [
                ],
                "url": {
                    "raw": "https://localhost"
                },
                "body": {
                    "mode": "urlencoded",
                    "urlencoded": [
                        {"key": "name", "value": "Jane Doe"}
                    ]
                }
            }""".trimIndent()

        val request = postmanItemRequest(parsedJSON(postmanJSON) as JSONObjectValue)

        assertThat(request.first).isEqualTo("https://localhost")
        assertThat(request.second.formFields).isEqualTo(mapOf("name" to "Jane Doe"))
    }

    @Test
    fun `should convert a Postman request with multiple parts`() {
        val postmanJSON = """{
                "method": "POST",
                "header": [
                ],
                "url": {
                    "raw": "https://localhost"
                },
                "body": {
                    "mode": "formdata",
                    "formdata": [
                        {"key": "name", "value": "Jane Doe"}
                    ]
                }
            }""".trimIndent()

        val request = postmanItemRequest(parsedJSON(postmanJSON) as JSONObjectValue)

        assertThat(request.first).isEqualTo("https://localhost")
        assertThat(request.second.multiPartFormData).isEqualTo(listOf(MultiPartContentValue("name", StringValue("Jane Doe"))))
    }

    @Test
    fun `should convert a Postman request containing a file`() {
        val postmanJSON = """{
                "method": "POST",
                "header": [
                ],
                "url": {
                    "raw": "https://localhost"
                },
                "body": {
                    "mode": "file",
                }
            }""".trimIndent()

        assertThatThrownBy { postmanItemRequest(parsedJSON(postmanJSON) as JSONObjectValue) }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `should convert a Postman response with a header`() {
        val postmanJSON = """{
                "code": 200,
                "header": [
                    {"key": "X-Data", "value": "10"}
                ]
            }""".trimIndent()

        val response = postmanItemResponse(parsedJSON(postmanJSON) as JSONObjectValue)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers).isEqualTo(mapOf("X-Data" to "10"))
    }

    @Test
    fun `should convert a Postman response with no headers and a body`() {
        val postmanJSON = """{
                "code": 200,
                "header": [
                ],
                "body": "{\"one\": 1}"
            }""".trimIndent()

        val response = postmanItemResponse(parsedJSON(postmanJSON) as JSONObjectValue)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers).isEqualTo(mapOf<String, String>())
        assertThat(response.body).isEqualTo(parsedJSON("""{"one": 1}"""))
    }

    @Test
    fun `should generated named stubs from Postman examples`() {
        val postmanRequestJSON = """{
                "method": "POST",
                "header": [
                    {"key": "X-Header", "value": "10"}
                ],
                "url": {
                    "raw": "https://localhost"
                }
            }""".trimIndent()

        val postmanExampleJSON = """{
                "name": "Original name",
                "originalRequest": $postmanRequestJSON,
                "code": 200,
                "header": [
                ]
        }"""

        val namedStubs = namedStubsFromPostmanResponses(listOf(parsedJSON(postmanExampleJSON)))
        val stub = namedStubs.single()
        assertThat(stub.first.host).isEqualTo("localhost")
        assertThat(stub.first.scheme).isEqualTo("https")
        assertThat(stub.first.port).isEqualTo(-1)
        assertThat(stub.first.originalBaseURL).isEqualTo("https://localhost")

        assertThat(stub.second.name).isEqualTo("Original name")

        val request = stub.second.stub
        assertThat(request.request.method).isEqualTo("POST")
        assertThat(request.request.headers).isEqualTo(mapOf("X-Header" to "10"))
        assertThat(request.request.path).isEqualTo("")

        val response = stub.second.stub.response
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isEqualTo(EmptyString)
        assertThat(response.headers).isEqualTo(emptyMap<String, String>())
    }

    @Test
    fun `Postman to spec with raw format request body`() {
        val postmanContent = """{
	"info": {
		"_postman_id": "8bd0c42a-983a-492e-99bd-a2f1936bc02e",
		"name": "Test API",
		"schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
	},
	"item": [
		{
			"name": "With no body or params",
			"request": {
				"method": "GET",
				"header": [],
				"url": {
					"raw": "http://localhost:9000/stuff",
					"protocol": "http",
					"host": [
						"localhost"
					],
					"port": "9000",
					"path": [
						"stuff"
					]
				}
			},
			"response": []
		},
		{
			"name": "With JSON body",
			"request": {
				"method": "POST",
				"header": [],
				"body": {
					"mode": "raw",
					"raw": "{\"data\": \"value\"}",
					"options": {
						"raw": {
							"language": "json"
						}
					}
				},
				"url": {
					"raw": "http://localhost:9000/stuff",
					"protocol": "http",
					"host": [
						"localhost"
					],
					"port": "9000",
					"path": [
						"stuff"
					]
				}
			},
			"response": []
		},
		{
			"name": "With query",
			"request": {
				"method": "GET",
				"header": [],
				"url": {
					"raw": "http://localhost:9000/stuff?one=1",
					"protocol": "http",
					"host": [
						"localhost"
					],
					"port": "9000",
					"path": [
						"stuff"
					],
					"query": [
						{
							"key": "one",
							"value": "1"
						}
					]
				}
			},
            "response": [
				{
					"name": "Square Of A Number 2",
					"originalRequest": {
						"method": "POST",
						"header": [],
						"body": {
							"mode": "raw",
							"raw": "10",
							"options": {
								"raw": {
									"language": "text"
								}
							}
						},
						"url": {
							"raw": "http://localhost:9000/square",
							"protocol": "http",
							"host": [
								"localhost"
							],
							"port": "9000",
							"path": [
								"square"
							]
						}
					},
					"status": "OK",
					"code": 200,
					"_postman_previewlanguage": "plain",
					"header": [
						{
							"key": "Vary",
							"value": "Origin"
						},
						{
							"key": "$SPECMATIC_RESULT_HEADER",
							"value": "success"
						},
						{
							"key": "Content-Length",
							"value": "3"
						},
						{
							"key": "Content-Type",
							"value": "text/plain"
						},
						{
							"key": "Connection",
							"value": "1000"
						}
					],
					"cookie": [],
					"body": "100"
				}
            ]
        },
		{
			"name": "With form fields",
			"request": {
				"method": "POST",
				"header": [],
				"body": {
					"mode": "urlencoded",
					"urlencoded": [
						{
							"key": "field1",
							"value": "10",
							"type": "text"
						}
					]
				},
				"url": {
					"raw": "http://localhost:9000/stuff",
					"protocol": "http",
					"host": [
						"localhost"
					],
					"port": "9000",
					"path": [
						"stuff"
					]
				}
			},
			"response": []
		},
		{
			"name": "With form data",
			"request": {
				"method": "POST",
				"header": [],
				"body": {
					"mode": "formdata",
					"formdata": [
						{
							"key": "part1",
							"value": "10",
							"type": "text"
						}
					]
				},
				"url": {
					"raw": "http://localhost:9000/stuff",
					"protocol": "http",
					"host": [
						"localhost"
					],
					"port": "9000",
					"path": [
						"stuff"
					]
				}
			},
			"response": []
		}
	],
	"protocolProfileBehavior": {}
}"""

        val info = postmanCollectionToGherkin(postmanContent)
        val (_, gherkinString, _, stubs) = info.first()

        println(gherkinString)

        val expectedGherkinString = """Feature: Test API
  Scenario: With no body or params
    When GET /stuff
    Then status 200
    And response-header Connection (string)
    And response-header Content-Type application/json
    And response-body (integer)
  
  Scenario: With JSON body
    Given type RequestBody
      | data | (string) |
    When POST /stuff
    And request-body (RequestBody)
    Then status 200
    And response-header Connection (string)
    And response-header Content-Type application/json
    And response-body (integer)
  
    Examples:
    | data |
    | value |
  
  Scenario: With query
    When GET /stuff?one=(integer)
    Then status 200
    And response-header Connection (string)
    And response-header Content-Type application/json
    And response-body (integer)
  
    Examples:
    | one |
    | 1 |
  
  Scenario: Square Of A Number 2
    When POST /square
    And request-body (RequestBody: integer)
    Then status 200
    And response-header Connection (integer)
    And response-header Content-Type text/plain
    And response-body (integer)
  
    Examples:
    | RequestBody |
    | 10 |
  
  Scenario: With form fields
    When POST /stuff
    And form-field field1 (integer)
    Then status 200
    And response-header Connection (string)
    And response-header Content-Type application/json
    And response-body (integer)
  
    Examples:
    | field1 |
    | 10 |
  
  Scenario: With form data
    When POST /stuff
    And request-part part1 (integer)
    Then status 200
    And response-header Connection (string)
    And response-header Content-Type application/json
    And response-body (integer)
  
    Examples:
    | part1 |
    | 10 |"""

        assertThat(gherkinString.trim()).isEqualTo(expectedGherkinString.trim())

        validate(gherkinString, stubs, mapOf("Content-Type" to "text/plain"))
    }

    @Test
    fun `should convert response info to an http response`() {
        val response = postmanItemResponse(parsedJSON("""				{
					"name": "Square Of A Number 2",
					"status": "OK",
					"code": 200,
					"_postman_previewlanguage": "plain",
					"header": [
						{
							"key": "X-Header",
							"value": "right value"
						}
					],
					"cookie": [],
					"body": "100"
				}""") as JSONObjectValue)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isEqualTo(NumberValue(100))
        assertThat(response.headers).hasSize(1)
        assertThat(response.headers.getOrDefault("X-Header", "does not exist")).isEqualTo("right value")
    }

    @Test
    fun `postman response conversion to spec`() {
        val namedStubs = namedStubsFromPostmanResponses((parsedJSON("""[
				{
					"name": "Square Of A Number 2",
					"originalRequest": {
						"method": "POST",
						"header": [],
						"body": {
							"mode": "raw",
							"raw": "10",
							"options": {
								"raw": {
									"language": "text"
								}
							}
						},
						"url": {
							"raw": "http://localhost:9000/square",
							"protocol": "http",
							"host": [
								"localhost"
							],
							"port": "9000",
							"path": [
								"square"
							]
						}
					},
					"status": "OK",
					"code": 200,
					"_postman_previewlanguage": "plain",
					"header": [
						{
							"key": "X-Header",
							"value": "right value"
						}
					],
					"cookie": [],
					"body": "100"
				}
            ]""") as JSONArrayValue).list)

        assertThat(namedStubs).hasSize(1)

        val namedStub = namedStubs.first()
        assertThat(namedStub.second.name).isEqualTo("Square Of A Number 2")

        val request = namedStub.second.stub.request
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/square")
        assertThat(request.headers).hasSize(0)
        assertThat(request.body).isEqualTo(NumberValue(10))

        val response = namedStub.second.stub.response
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isEqualTo(NumberValue(100))
        assertThat(response.headers).hasSize(1)
        assertThat(response.headers.getOrDefault("X-Header", "does not exist")).isEqualTo("right value")
    }

    private fun validate(gherkinString: String, stubs: List<NamedStub>, additionalHeaders: Map<String, String> = emptyMap()) {
        val behaviour = parseGherkinStringToFeature(gherkinString)
        val cleanedUpStubs = stubs.map { it.stub }.map {
            it.copy(
                response = it.response.copy(
                    headers = additionalHeaders.plus(dropConversionExcludedHeaders(it.response.headers))
                )
            )
        }

        for(stub in cleanedUpStubs) {
            behaviour.matchingStub(stub)
        }
    }

    @Test
    fun `postman request with body to HttpRequest object with body`() {
        val (baseURL, request) = postmanItemRequest(parsedJSON("""{
						"method": "POST",
						"header": [],
						"body": {
							"mode": "raw",
							"raw": "10",
							"options": {
								"raw": {
									"language": "text"
								}
							}
						},
						"url": {
							"raw": "http://localhost:9000/square",
							"protocol": "http",
							"host": [
								"localhost"
							],
							"port": "9000",
							"path": [
								"square"
							]
						}
					}""") as JSONObjectValue)

        assertThat(baseURL).isEqualTo("http://localhost:9000")

        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/square")
        assertThat(request.headers).isEmpty()
        assertThat(request.body).isEqualTo(NumberValue(10))
    }

    @Test
    fun `postman request with headers to HttpRequest object with body`() {
        val (baseURL, request) = postmanItemRequest(parsedJSON("""{
						"method": "GET",
						"header": [
                            {
                                "key": "X-Header",
                                "value": "data"
                            }
                        ],
						"url": {
							"raw": "http://localhost:9000/square",
							"protocol": "http",
							"host": [
								"localhost"
							],
							"port": "9000",
							"path": [
								"square"
							]
						}
					}""") as JSONObjectValue)

        assertThat(baseURL).isEqualTo("http://localhost:9000")

        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/square")
        assertThat(request.headers.getValue("X-Header")).isEqualTo("data")
        assertThat(request.body).isEqualTo(EmptyString)
    }

    @Test
    fun `postman request with form fields to HttpRequest object with form fields`() {
        val (baseURL, request) = postmanItemRequest(parsedJSON("""{
				"method": "POST",
				"header": [],
				"body": {
					"mode": "urlencoded",
					"urlencoded": [
						{
							"key": "field1",
							"value": "10",
							"type": "text"
						}
					]
				},
				"url": {
					"raw": "http://localhost:9000/stuff",
					"protocol": "http",
					"host": [
						"localhost"
					],
					"port": "9000",
					"path": [
						"stuff"
					]
				}
            }""") as JSONObjectValue)

        assertThat(baseURL).isEqualTo("http://localhost:9000")

        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/stuff")
        assertThat(request.body).isEqualTo(EmptyString)
        assertThat(request.formFields.getOrDefault("field1", "does not exist")).isEqualTo("10")
    }

    @Test
    fun `postman request with form data to HttpRequest object with form data content`() {
        val (baseURL, request) = postmanItemRequest(parsedJSON("""{
				"method": "POST",
				"header": [],
				"body": {
					"mode": "formdata",
					"formdata": [
						{
							"key": "part1",
							"value": "10",
							"type": "text"
						}
					]
				},
				"url": {
					"raw": "http://localhost:9000/stuff",
					"protocol": "http",
					"host": [
						"localhost"
					],
					"port": "9000",
					"path": [
						"stuff"
					]
				}
			}""") as JSONObjectValue)

        assertThat(baseURL).isEqualTo("http://localhost:9000")

        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/stuff")
        assertThat(request.body).isEqualTo(EmptyString)
        assertThat(request.formFields).isEmpty()
        val part = request.multiPartFormData.first() as MultiPartContentValue
        assertThat(part.name).isEqualTo("part1")
        assertThat(part.content).isEqualTo(NumberValue(10))
    }

    @Test
    fun `parses an items list inside postman with request and response into multiple scenarios`() {
        val postmanString = """{
            "info": {
				"name": "Test collection",
				"schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
            },
            "item": [{
			"name": "With query",
			"request": {
				"method": "GET",
				"header": [],
				"url": {
					"raw": "http://localhost:9000/stuff?one=1",
					"protocol": "http",
					"host": [
						"localhost"
					],
					"port": "9000",
					"path": [
						"stuff"
					],
					"query": [
						{
							"key": "one",
							"value": "1"
						}
					]
				}
			},
            "response": [
				{
					"name": "Square Of A Number 2",
					"originalRequest": {
						"method": "POST",
						"header": [],
						"url": {
							"raw": "http://localhost:9000/square",
							"protocol": "http",
							"host": [
								"localhost"
							],
							"port": "9000",
							"path": [
								"square"
							]
						}
					},
					"status": "OK",
					"code": 200,
					"_postman_previewlanguage": "plain",
					"header": [
						{
							"key": "Vary",
							"value": "Origin"
						},
						{
							"key": "$SPECMATIC_RESULT_HEADER",
							"value": "success"
						},
						{
							"key": "Content-Length",
							"value": "3"
						},
						{
							"key": "Content-Type",
							"value": "text/plain"
						},
						{
							"key": "Connection",
							"value": "1000"
						}
					],
					"cookie": [],
					"body": "100"
				}
            ]
        }]}"""

        val postmanCollection = stubsFromPostmanCollection(postmanString)

        val stub1 = postmanCollection.stubs[0]
        assertThat(stub1.second.name).isEqualTo("With query")
        assertThat(stub1.second.stub.request.method).isEqualTo("GET")
        assertThat(stub1.second.stub.request.queryParams.getOrDefault("one", "not found")).isEqualTo("1")

        val stub2 = postmanCollection.stubs[1]
        assertThat(stub2.second.name).isEqualTo("Square Of A Number 2")
        assertThat(stub2.second.stub.request.method).isEqualTo("POST")
        assertThat(stub2.second.stub.response.headers).hasSize(2)
    }

    companion object {
        var stub: HttpStub? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            val gherkin = """Feature: Number API

Scenario: With no body or params
  When GET /stuff
  Then status 200
  And response-header Content-Type application/json
  And response-body (number)

Scenario: With query
  When GET /stuff?one=(string)
  Then status 200
  And response-header Content-Type application/json
  And response-body (number)

Scenario: With JSON body
  Given type Request
    | data | (string) |
  When POST /stuff
  And request-body (Request)
  Then status 200
  And response-header Content-Type application/json
  And response-body (number)

Scenario: With form fields
  When POST /stuff
  And form-field field1 (string)
  Then status 200
  And response-header Content-Type application/json
  And response-body (number)

Scenario: With form data
  When POST /stuff
  And request-part part1 (string)
  Then status 200
  And response-header Content-Type application/json
  And response-body (number)
"""

            val contractBehaviour = parseGherkinStringToFeature(gherkin)
            stub = HttpStub(contractBehaviour)
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            stub?.close()
        }
    }
}
