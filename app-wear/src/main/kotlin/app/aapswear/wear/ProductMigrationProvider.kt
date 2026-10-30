package app.aapswear.wear

import app.aapswear.uishared.ProductDataMigrationProvider
import app.aapswear.uishared.ProductMigrationLifecycle

class ProductMigrationProvider : ProductDataMigrationProvider() {
    override val legacyPackage = "app.aapswear"
    override val targetPackage = "app.sugarlicious"

    override fun beforeSnapshot() = ProductMigrationLifecycle.prepareSource(requireNotNull(context))

    override fun afterMigrationAborted() = ProductMigrationLifecycle.restoreSource(requireNotNull(context))

    override fun afterMigrationAccepted() = ProductMigrationLifecycle.retireSource(requireNotNull(context))
}
