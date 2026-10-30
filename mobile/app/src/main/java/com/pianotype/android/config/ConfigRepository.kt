package com.pianotype.android.config

import android.content.Context
import java.io.File

/**
 * メモリ + ファイル (filesDir/config.toml) で設定を保持。
 * Activity と IME Service は同一プロセスなので、リスナー経由で
 * 変更をリアルタイムに伝搬できる。
 */
object ConfigRepository {
    private const val FILE_NAME = "config.toml"

    private val listeners = mutableListOf<(Config) -> Unit>()
    @Volatile private var cached: Config = Config()

    val current: Config get() = cached

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(context: Context) {
        val f = file(context)
        if (f.exists()) {
            runCatching {
                cached = ensureKanaMapping(TomlConfig.parse(f.readText()))
            }
        } else {
            // 初回起動: おすすめ設定を書き込む
            runCatching {
                val def = DefaultConfig.parse()
                cached = def
                f.writeText(TomlConfig.serialize(def))
            }
        }
        notifyListeners()
    }

    /**
     * おすすめ設定に戻す。既存の設定は上書きされる。
     */
    @Synchronized
    fun resetToDefault(context: Context) {
        val def = DefaultConfig.parse()
        update(context, def)
    }

    @Synchronized
    fun update(context: Context, config: Config) {
        val ensured = ensureKanaMapping(config)
        cached = ensured
        file(context).writeText(TomlConfig.serialize(ensured))
        notifyListeners()
    }

    /** かなモードのマッピングが空なら、既定のかな配列を補う。 */
    private fun ensureKanaMapping(config: Config): Config =
        if (config.kanaMapping.isEmpty()) config.copy(kanaMapping = DefaultConfig.kanaMappings)
        else config

    /** TOML テキストを直接ロードして保存。 */
    @Synchronized
    fun loadFromToml(context: Context, toml: String) {
        val parsed = TomlConfig.parse(toml)
        update(context, parsed)
    }

    /** 他PCで編集した内容とマージするのではなく、丸ごと差し替える。 */
    @Synchronized
    fun replaceAll(context: Context, config: Config) {
        update(context, config)
    }

    @Synchronized
    fun addListener(l: (Config) -> Unit) {
        listeners.add(l)
        l(cached)
    }

    @Synchronized
    fun removeListener(l: (Config) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        val snapshot = listeners.toList()
        val c = cached
        snapshot.forEach { runCatching { it(c) } }
    }
}
