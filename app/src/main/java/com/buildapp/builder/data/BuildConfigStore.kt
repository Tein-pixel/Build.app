package com.buildapp.builder.data

import android.content.Context

data class BuildConfig(
    val openRouterKey: String = "",
    val modelId: String = "cohere/north-mini-code:free",
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubBranch: String = "main",
    val githubToken: String = ""
)

class BuildConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("buildapp_config", Context.MODE_PRIVATE)
    private val vault = SecretVault(context)

    fun load(): BuildConfig = BuildConfig(
        openRouterKey = vault.get("openrouter_key").orEmpty(),
        modelId = prefs.getString("model_id", "cohere/north-mini-code:free").orEmpty(),
        githubOwner = prefs.getString("github_owner", "").orEmpty(),
        githubRepo = prefs.getString("github_repo", "").orEmpty(),
        githubBranch = prefs.getString("github_branch", "main").orEmpty(),
        githubToken = vault.get("github_token").orEmpty()
    )

    fun save(config: BuildConfig) {
        vault.put("openrouter_key", config.openRouterKey.trim())
        vault.put("github_token", config.githubToken.trim())
        prefs.edit()
            .putString("model_id", config.modelId.trim().ifBlank { "cohere/north-mini-code:free" })
            .putString("github_owner", config.githubOwner.trim())
            .putString("github_repo", config.githubRepo.trim())
            .putString("github_branch", config.githubBranch.trim().ifBlank { "main" })
            .apply()
    }
}
