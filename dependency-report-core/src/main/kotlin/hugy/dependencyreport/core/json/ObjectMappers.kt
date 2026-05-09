package hugy.dependencyreport.core.json

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.module.kotlin.jsonMapper

object ObjectMappers {
    val json: ObjectMapper = jsonMapper {
        enable(SerializationFeature.INDENT_OUTPUT)
    }
}
