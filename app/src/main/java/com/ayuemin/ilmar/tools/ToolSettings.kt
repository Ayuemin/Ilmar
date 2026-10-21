package com.ayuemin.ilmar.tools

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
internal fun ToolSettings() {
    Text("Глобальные разрешения ниже. В чате выбери инструменты через «+» и включи агента у модели с поддержкой tools.")
    SearchSettings()
    HorizontalDivider()
    com.ayuemin.ilmar.github.GitHubSettings()
    HorizontalDivider()
    com.ayuemin.ilmar.alpine.AlpineSettings()
    HorizontalDivider()
    Text("Размеры файлов, число вложений, PDF-страниц, таймауты, раунды агента, память и журнал настраиваются глобально в разделе «Расширенные лимиты».")
    Text("Созданные файлы сразу появляются в истории и сохраняются через системный диалог. HTML/SVG/XML доступны только для сохранения. Большие файлы читаются через read_file частями.")
    Text("Память и планы изолированы по чатам. Полный диагностический журнал и его выгрузка — в разделе «Диагностика».")
}
