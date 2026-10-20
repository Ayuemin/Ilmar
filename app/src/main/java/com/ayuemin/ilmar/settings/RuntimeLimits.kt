package com.ayuemin.ilmar.settings

import android.content.Context

internal data class LimitField(val key: String, val group: String, val label: String, val default: Int, val maximum: Int, val zero: Boolean = false)

/** Global operational settings. Zero disables only explicitly optional budgets, deadlines and quotas. */
internal object RuntimeLimits {
    val fields = listOf(
        LimitField("rounds", "Агент", "Модельных раундов; 0 — без лимита", 64, 100000, true),
        LimitField("calls", "Агент", "Вызовов tools; 0 — без лимита", 256, 1000000, true),
        LimitField("task_seconds", "Агент", "Время задачи, секунд; 0 — без лимита", 0, 604800, true),
        LimitField("model_seconds", "Агент", "Время запроса модели, секунд; 0 — без лимита", 0, 604800, true),
        LimitField("tool_seconds", "Агент", "Время одного tool, секунд; 0 — без лимита", 120, 86400, true),
        LimitField("repeat_threshold", "Агент", "Повторений без прогресса; 0 — отключить защиту", 3, 1000, true),
        LimitField("argument_kib", "Агент", "Аргументы tool, КиБ", 16384, 65536),
        LimitField("result_kib", "Агент", "Результат tool, КиБ", 2048, 65536),
        LimitField("trace_tokens", "Агент", "Рабочая история tools, оценка токенов", 12000, 1000000),
        LimitField("create_kib", "Файлы", "Создаваемый UTF-8 файл, КиБ", 16384, 65536),
        LimitField("import_mib", "Файлы", "Импорт одного файла, МиБ", 128, 1024),
        LimitField("text_kib", "Файлы", "Извлечённый текст документа, КиБ", 8192, 65536),
        LimitField("attachments", "Файлы", "Вложений в сообщении", 32, 1000),
        LimitField("message_chars", "Файлы", "Текст вложений сообщения, символов", 2000000, 50000000),
        LimitField("pdf_pages", "Файлы", "Страниц PDF", 100, 2000),
        LimitField("request_images_mib", "Файлы", "Изображения одного запроса модели, МиБ", 64, 512),
        LimitField("images_mib", "Файлы", "Нормализованные изображения документа, МиБ", 64, 512),
        LimitField("storage_mib", "Файлы", "Квота создания файлов, МиБ; 0 — без квоты", 0, 1000000, true),
        LimitField("xml_mib", "Файлы", "XML одного компонента DOCX/XLSX, МиБ", 16, 128),
        LimitField("zip_mib", "Файлы", "Распакованный XLSX, МиБ", 64, 512),
        LimitField("zip_entries", "Файлы", "Компонентов DOCX/XLSX", 10000, 100000),
        LimitField("sheets", "Файлы", "Листов XLSX", 100, 1000),
        LimitField("cells", "Файлы", "Ячеек на листе XLSX", 200000, 2000000),
        LimitField("fetch_kib", "Интернет", "Ответ fetch/search, КиБ", 8192, 65536),
        LimitField("web_seconds", "Интернет", "Сетевой таймаут, секунд", 60, 3600),
        LimitField("excerpt_chars", "Интернет", "Текст tool в результате, символов", 64000, 2000000),
        LimitField("search_results", "Интернет", "Результатов поиска", 10, 100),
        LimitField("cache_minutes", "Интернет", "Кэш, минут; 0 — отключить", 5, 10080, true),
        LimitField("cache_entries", "Интернет", "Записей кэша", 256, 100000),
        LimitField("embedding_chars", "Семантический поиск", "Символов в индексируемом фрагменте", 2000, 16000),
        LimitField("embedding_batch", "Семантический поиск", "Фрагментов на запрос эмбеддингов", 16, 128),
        LimitField("embedding_seconds", "Семантический поиск", "Таймаут эмбеддингов, секунд", 60, 3600),
        LimitField("embedding_response_kib", "Семантический поиск", "Ответ эмбеддингов, КиБ", 8192, 65536),
        LimitField("retrieval_hits", "Семантический поиск", "Найденных фрагментов", 5, 100),
        LimitField("retrieval_tokens", "Семантический поиск", "Фрагменты в контексте, оценка токенов", 2000, 100000),
        LimitField("rules_chars", "Память и журнал", "Символов закреплённых правил", 32000, 2000000),
        LimitField("summary_tokens", "Память и журнал", "Максимальный вывод Compact, токенов", 1024, 100000),
        LimitField("summary_validation_tokens", "Память и журнал", "Допустимый размер сводки, оценка токенов", 4096, 1000000),
        LimitField("memory_records", "Память и журнал", "Записей памяти на чат; 0 — без лимита", 0, 1000000, true),
        LimitField("memory_chars", "Память и журнал", "Символов в записи памяти", 32000, 2000000),
        LimitField("plan_steps", "Память и журнал", "Шагов плана", 200, 10000),
        LimitField("log_events", "Память и журнал", "Записей журнала", 2000, 100000),
        LimitField("log_chars", "Память и журнал", "Символов подробностей на событие", 65536, 1000000)
    )
    @Volatile private var prefs: android.content.SharedPreferences? = null
    fun install(context: Context) { prefs = context.applicationContext.getSharedPreferences("ilmar_limits", Context.MODE_PRIVATE) }
    fun get(key: String): Int {
        val field = fields.first { it.key == key }
        return (prefs?.getInt(key, field.default) ?: field.default).coerceIn(if (field.zero) 0 else 1, field.maximum)
    }
    fun bytes(key: String) = get(key) * 1024
    fun mib(key: String) = get(key).toLong() * 1024 * 1024
    fun save(values: Map<String, Int>) {
        fields.forEach { field -> require(values.getValue(field.key) in (if (field.zero) 0 else 1)..field.maximum) { "${field.label}: недопустимое значение" } }
        val editor = checkNotNull(prefs).edit(); values.forEach { (key, value) -> editor.putInt(key, value) }; check(editor.commit())
    }
    fun reset() { checkNotNull(prefs).edit().clear().commit() }
}
