package hugy.dependencyreport.core.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object ObjectMappers {
    val json: ObjectMapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
}
