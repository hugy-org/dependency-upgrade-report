package hugy.dependencyreport.core.fetch

import hugy.dependencyreport.core.model.ReleaseSource
import hugy.dependencyreport.core.model.UpgradeTarget

data class ReleaseFetchRequest(
    val source: ReleaseSource,
    val target: UpgradeTarget,
)
