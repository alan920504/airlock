package com.ing.wbaa.gargoyle.proxy.data

import org.scalatest.{ DiagrammedAssertions, WordSpec }

class JsonProtocolsSpec extends WordSpec with DiagrammedAssertions with JsonProtocols {

  import spray.json._

  "Json protocols" should {
    "parse a User" that {
      "has a group" in {
        val jsonString =
          """{
            | "userName": "user",
            | "userGroup": "group1",
            | "accessKey": "accesskey",
            | "secretKey": "secretkey"
            |}""".stripMargin
        val result = jsonString.parseJson.convertTo[User]
        assert(result == User("user", Some("group1"), "accesskey", "secretkey"))
      }

      "does not have a group" in {
        val jsonString =
          """{
            | "userName": "user",
            | "accessKey": "accesskey",
            | "secretKey": "secretkey"
            |}""".stripMargin
        val result = jsonString.parseJson.convertTo[User]
        assert(result == User("user", None, "accesskey", "secretkey"))
      }

      "fail when fields are missing" in {
        val jsonString =
          """{
            | "userName": "user"
            |}""".stripMargin

        assertThrows[spray.json.DeserializationException] {
          jsonString.parseJson.convertTo[User]
        }
      }
    }
  }
}