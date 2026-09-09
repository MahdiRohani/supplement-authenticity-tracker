package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.FeatureFlags

class GetFeatureFlagsUseCase(
    private val repository: ProductRepository,
) {
    suspend operator fun invoke(): FeatureFlags = repository.getFeatureFlags()
}
