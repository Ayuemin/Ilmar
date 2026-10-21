package com.ayuemin.ilmar.github

import com.ayuemin.ilmar.alpine.AlpineRuntime
import org.json.JSONArray
import org.json.JSONObject

/** Native Git operations using the installed Alpine git package. Shell syntax is application-owned. */
internal class GitHubGit(
    private val runtime: AlpineRuntime,
    private val token: () -> String?,
    private val author: () -> Pair<String, String>,
    private val allowed: (Long) -> Boolean,
    private val approve: suspend (Long, String, String) -> Boolean,
    private val runner: suspend (String, Int, () -> Boolean) -> String = { command, seconds, authorized -> runtime.execute(command, "/root", seconds, authorized) }
) {
    companion object {
        val reads = setOf("status", "diff", "log")
        val actions = reads + setOf("init", "clone", "fetch", "pull", "add", "commit", "branch", "push")
        fun repository(value: String): String {
            require(value.matches(Regex("[A-Za-z0-9_-]{1,100}/[A-Za-z0-9_.-]{1,100}")) && value.substringAfter('/') !in setOf(".", "..")) { "repository: owner/name" }
            return value
        }
        fun directory(value: String): String {
            require(value.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,79}")) && !value.endsWith('.')) { "directory: простое имя каталога репозитория" }
            return "/root/repos/$value"
        }
        fun branch(value: String): String {
            require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,179}")) && !value.contains("..") && !value.contains("//") && !value.endsWith('/') && !value.endsWith('.') && value.split('/').none { it.endsWith(".lock") || it.startsWith('.') }) { "Недопустимое имя ветки" }
            return value
        }
    }

    suspend fun execute(chat: Long, args: JSONObject): String {
        val action = args.getString("action"); require(action in actions) { "Неизвестная операция Git" }
        if (action in setOf("push", "pull", "fetch", "branch")) require(args.has("branch")) { "Укажи branch явно" }
        val repository = repository(args.getString("repository"))
        val directory = directory(args.optString("directory", repository.substringAfter('/')))
        val branch = args.optString("branch", "main").let(::branch)
        val message = args.optString("message")
        val paths = args.optJSONArray("paths") ?: JSONArray()
        val seconds = args.optInt("timeout_seconds", 120); require(seconds in 1..300)
        val network = action in setOf("clone", "fetch", "pull", "push")
        val credential = if (network) token()?.takeIf { it.isNotBlank() } ?: error("Добавь токен GitHub в настройках") else null
        if (action == "commit") require(message.isNotBlank() && message.length <= 4096) { "Нужно сообщение коммита до 4096 символов" }
        val selected = (0 until paths.length()).map { paths.getString(it).also { path ->
            require(path.isNotBlank() && path.length <= 1024 && !path.startsWith('/') && path.split('/').none { it == ".." } && '\u0000' !in path) { "Путь файла должен быть внутри репозитория" }
        } }
        if (action == "add") require(selected.size in 1..100) { "Укажи paths для git add" }
        val identity = author()
        if (action == "commit") require(identity.first.isNotBlank() && identity.second.isNotBlank() && (identity.first + identity.second).none { it == '\u0000' || it == '\n' || it == '\r' }) { "Настрой имя и email автора Git в настройках GitHub" }
        check(allowed(chat)) { "Разрешение Git отозвано" }
        if (action !in reads) check(approve(chat, "Git · $action", "Репозиторий: $repository\nКаталог: $directory\nВетка: $branch\nТаймаут: $seconds с\n\n${if (action == "commit") message else selected.joinToString("\n")}\n\nPush выполняется без force, pull только fast-forward. Git hooks отключены.")) { "Операция Git отменена" }
        check(allowed(chat) && (!network || token() == credential)) { "Разрешение или токен Git изменены" }
        suspend fun run(helper: String?): String {
            val q = GitCredentialBridge::quote
            val git = "git --no-pager -c core.hooksPath=/dev/null -c credential.helper= -c credential.useHttpPath=true " +
                "-c http.followRedirects=false -c http.sslVerify=true -c http.extraHeader= -c commit.gpgSign=false -c tag.gpgSign=false " +
                "-c core.pager=cat " + (helper?.let { "-c credential.helper=${q(it)} " } ?: "")
            val local = "$git -C ${q(directory)} "
            val remote = q("https://github.com/$repository.git")
            val command = when (action) {
                "init" -> "mkdir -p ${q(directory)} && $git init --initial-branch=${q(branch)} ${q(directory)}"
                "clone" -> "mkdir -p /root/repos && $git clone " + (if (args.has("branch")) "--branch ${q(branch)} " else "") + "-- $remote ${q(directory)}"
                "status" -> local + "status --short --branch"
                "diff" -> local + "diff --no-ext-diff --no-textconv HEAD --"
                "log" -> local + "log -20 --oneline --no-show-signature"
                "fetch" -> local + "fetch --no-tags -- $remote ${q(branch)}"
                "pull" -> local + "pull --ff-only --no-rebase -- $remote ${q(branch)}"
                "add" -> local + "add -- " + selected.joinToString(" ", transform = q)
                "commit" -> local + "-c user.name=${q(identity.first)} -c user.email=${q(identity.second)} commit --message=${q(message)}"
                "branch" -> local + "checkout -b ${q(branch)}"
                "push" -> local + "push --porcelain -- $remote ${q("HEAD:refs/heads/$branch")}"
                else -> error("Неизвестная операция")
            }
            val prefix = "GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0 "
            val full = "command -v git >/dev/null || { echo 'Установи Git: apk add git ca-certificates'; exit 127; }; export $prefix; $command"
            val result = runner(full, seconds) { allowed(chat) && (!network || token() == credential) }
            return if (credential == null) result else result.replace(credential, "[REDACTED]")
        }
        return if (credential == null) run(null) else GitCredentialBridge.withCredential(credential, repository) { run(it) }
    }
}
