package com.macsia.teatiers.cli

import com.macsia.teatiers.service.ReviewService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Local operator review CLI (decision #137-C7). This REPLACES the removed unauthenticated HTTP review
 * controller: canonical-catalog mutation (approve-new / approve-merge / reject) is now reachable ONLY
 * by an operator who can run this process against the operator database -- over an SSH tunnel or a
 * direct local connection, never from the public Caddy listener. The authenticated actor is the
 * OS/login user running the command (override with `--reviewer=...`); it is recorded on every decision.
 *
 * Activated by the `review-cli` Spring profile, which forces a non-web context and skips the catalog
 * seed (see `application-review-cli.yml`). The process runs one command and exits. Usage:
 *
 * ```
 * java -jar server.jar --spring.profiles.active=review-cli pending [--limit=50]
 * java -jar server.jar --spring.profiles.active=review-cli count
 * java -jar server.jar --spring.profiles.active=review-cli approve-new  <decisionId> [--reviewer=alice]
 * java -jar server.jar --spring.profiles.active=review-cli approve-merge <decisionId> [--target=<teaId>] [--reviewer=alice]
 * java -jar server.jar --spring.profiles.active=review-cli reject       <decisionId> [--reviewer=alice]
 * ```
 */
@Component
@Profile("review-cli")
class ReviewCli(
    private val reviewService: ReviewService,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val exitCode = try {
            dispatch(
                positional = args.nonOptionArgs,
                reviewer = option(args, "reviewer")?.takeIf { it.isNotBlank() } ?: defaultReviewer(),
                limit = option(args, "limit")?.toIntOrNull() ?: DEFAULT_LIMIT,
                target = option(args, "target")?.toLongOrNull(),
            )
            0
        } catch (e: IllegalArgumentException) {
            // Operator-facing input error: print the message + usage, exit non-zero (no stack trace noise).
            log.error("review-cli: {}\n{}", e.message, USAGE)
            2
        } catch (e: Exception) {
            log.error("review-cli failed", e)
            1
        }
        // A non-web context with scheduling/async beans would otherwise linger; terminate explicitly.
        System.exit(SpringApplication.exit(context, ExitCodeGenerator { exitCode }))
    }

    /** Run one review command, printing its result to stdout. Throws [IllegalArgumentException] on bad input. */
    internal fun dispatch(positional: List<String>, reviewer: String, limit: Int, target: Long?) {
        val command = positional.firstOrNull()
            ?: throw IllegalArgumentException("missing command")
        when (command) {
            "pending" -> reviewService.pending(limit).also { println("${it.size} pending:") }.forEach { println(it) }
            "count" -> println(reviewService.pendingCount())
            "approve-new" -> println(reviewService.approveNew(decisionId(positional), reviewer))
            "approve-merge" -> println(reviewService.approveMerge(decisionId(positional), reviewer, target))
            "reject" -> println(reviewService.reject(decisionId(positional), reviewer))
            else -> throw IllegalArgumentException("unknown command '$command'")
        }
    }

    private fun decisionId(positional: List<String>): Long =
        positional.getOrNull(1)?.toLongOrNull()
            ?: throw IllegalArgumentException("'${positional.first()}' needs a numeric <decisionId>")

    private fun defaultReviewer(): String =
        System.getProperty("user.name")?.takeIf { it.isNotBlank() }?.let { "cli:$it" } ?: "cli:operator"

    private fun option(args: ApplicationArguments, name: String): String? =
        args.getOptionValues(name)?.firstOrNull()

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val USAGE =
            "usage: <pending [--limit=N] | count | approve-new <id> | approve-merge <id> [--target=teaId] " +
                "| reject <id>> [--reviewer=name]"
    }
}
