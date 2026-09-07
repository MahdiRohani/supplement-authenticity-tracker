package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.ProductListPage
import ir.aut.supplementtracker.core.model.ProductListQuery

class ListProductsUseCase(
    private val repository: ProductRepository,
) {
    suspend operator fun invoke(query: ProductListQuery): ProductListPage {
        require(query.page >= 1) { "page must be >= 1" }
        require(query.limit in 1..100) { "limit must be between 1 and 100" }
        return repository.list(query)
    }
}
