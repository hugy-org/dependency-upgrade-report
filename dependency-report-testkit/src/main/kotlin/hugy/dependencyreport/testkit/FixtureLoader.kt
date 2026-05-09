package hugy.dependencyreport.testkit

import java.nio.file.Path
import kotlin.io.path.readText

object FixtureLoader {
    fun text(path: Path): String = path.readText().replace("\r\n", "\n").trimEnd()
}
